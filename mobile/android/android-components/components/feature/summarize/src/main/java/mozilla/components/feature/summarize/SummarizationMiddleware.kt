/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mozilla.components.concept.llm.CloudLlmProvider
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.concept.llm.LlmSession
import mozilla.components.concept.llm.LocalLlmProvider
import mozilla.components.feature.summarize.content.ContentProvider
import mozilla.components.feature.summarize.ext.fetchLlm
import mozilla.components.feature.summarize.ext.mapToRichDocument
import mozilla.components.feature.summarize.ext.systemPrompt
import mozilla.components.feature.summarize.settings.SummarizationSettings
import mozilla.components.lib.llm.adk.create
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import kotlin.time.Duration.Companion.seconds

const val TAG = "SummarizationMiddleware"

/** The initial middleware for the summarization feature */
class SummarizationMiddleware(
    private val settings: SummarizationSettings,
    private val llmProvider: LlmProvider,
    private val contentProvider: ContentProvider,
    private val errorReporter: ErrorReporter,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Middleware<SummarizationState, SummarizationAction> {

    private var session: LlmSession? = null

    override fun invoke(
        store: Store<SummarizationState, SummarizationAction>,
        next: (SummarizationAction) -> Unit,
        action: SummarizationAction,
    ) {
        when (action) {
            is ViewAppeared -> scope.launch {
                if (needsShakeConsent(store.state)) {
                    store.dispatch(ShakeConsentRequested)
                } else {
                    observeProvider(store)
                }
            }
            OffDeviceSummarizationShakeConsentAction.CancelClicked -> scope.launch {
                settings.incrementShakeConsentRejectedCount()
            }
            OffDeviceSummarizationShakeConsentAction.AllowClicked -> scope.launch {
                settings.setHasConsentedToShake(true)
                observeProvider(store)
            }
            LlmProviderAction.ProviderAvailable -> scope.launch {
                (llmProvider as? CloudLlmProvider)?.prepare()
            }
            LlmProviderAction.ProviderNeedsDownload -> scope.launch {
                (llmProvider as? LocalLlmProvider)?.downloadIfNeeded()
            }
            is LlmProviderAction.ProviderInitialized -> scope.launch {
                observePrompt(store, action.model)
            }
            is SummarizationFailed -> scope.launch {
                errorReporter.report(TAG, action.exception)
            }
            is FollowUpSubmitted -> scope.launch {
                runFollowUp(store, action.question)
            }
            is ViewDismissed -> session = null
        }

        next(action)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun observePrompt(store: SummarizationStore, model: LlmModel) {
        try {
            withTimeout(SUMMARIZE_TIMEOUT) {
                val content = contentProvider.getContent().getOrThrow()

                store.dispatch(ContentExtracted(content))

                val newSession = LlmSession.create(
                    model = model,
                    systemPrompt = content.metadata.systemPrompt,
                    tools = listOf(PageContentTool { content.body }),
                )
                session = newSession

                newSession.send(content.body)
                    .mapToRichDocument(
                        pageTitle = content.metadata.pageTitle,
                        dispatcher = dispatcher,
                    )
                    .onCompletion { if (it == null) store.dispatch(SummarizationCompleted) }
                    .collect { store.dispatch(ReceivedParsedDocument(it)) }
            }
        } catch (e: TimeoutCancellationException) {
            store.dispatch(SummarizationFailed(e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.dispatch(SummarizationFailed(e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runFollowUp(store: SummarizationStore, question: String) {
        val currentSession = session ?: return
        try {
            currentSession.send(question)
                .mapToRichDocument(pageTitle = "", dispatcher = dispatcher)
                .onCompletion { if (it == null) store.dispatch(FollowUpCompleted) }
                .collect { store.dispatch(ReceivedFollowUpDocument(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.dispatch(SummarizationFailed(e))
        }
    }

    private suspend fun observeProvider(store: SummarizationStore) =
        llmProvider.fetchLlm.collect { store.dispatch(it) }

    private suspend fun needsShakeConsent(state: SummarizationState): Boolean =
        state is SummarizationState.Inert &&
            state.initializedWithShake &&
            !settings.getHasConsentedToShake().first()

    private companion object {
        val SUMMARIZE_TIMEOUT = 60.seconds
    }
}
