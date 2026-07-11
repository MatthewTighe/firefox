/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mozilla.components.concept.llm.CloudLlmProvider
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.concept.llm.LlmSession
import mozilla.components.concept.llm.LocalLlmProvider
import mozilla.components.feature.summarize.content.Content
import mozilla.components.feature.summarize.content.ContentProvider
import mozilla.components.feature.summarize.ext.fetchLlm
import mozilla.components.feature.summarize.ext.generalQaInstructions
import mozilla.components.feature.summarize.ext.instructionsFor
import mozilla.components.feature.summarize.ext.mapToRichDocument
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
    private var model: LlmModel? = null
    private var content: Content? = null

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
                prepareContent(store, action.model)
            }
            is SuggestionSelected -> scope.launch {
                runSuggestion(store, action.suggestion)
            }
            is FollowUpSubmitted -> scope.launch {
                runFollowUp(store, action.question)
            }
            is SummarizationFailed -> scope.launch {
                errorReporter.report(TAG, action.exception)
            }
            is ViewDismissed -> {
                session = null
                model = null
                content = null
            }
        }

        next(action)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareContent(store: SummarizationStore, model: LlmModel) {
        try {
            val content = contentProvider.getContent().getOrThrow()
            this.model = model
            this.content = content
            store.dispatch(ContentExtracted(content))
            store.dispatch(ReadyForInput(llmProvider.info))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.dispatch(SummarizationFailed(e))
        }
    }

    private suspend fun runSuggestion(store: SummarizationStore, suggestion: SummarizationSuggestion) {
        val content = content ?: return
        val model = model ?: return
        val newSession = LlmSession.create(
            model = model,
            systemPrompt = content.metadata.instructionsFor(suggestion),
            tools = listOf(PageContentTool { content.body }),
        )
        session = newSession
        stream(store) { newSession.send(content.body) }
    }

    private suspend fun runFollowUp(store: SummarizationStore, question: String) {
        val existingSession = session
        if (existingSession != null) {
            stream(store) { existingSession.send(question) }
            return
        }

        val content = content ?: return
        val model = model ?: return
        val newSession = LlmSession.create(
            model = model,
            systemPrompt = generalQaInstructions(content.metadata.language),
            tools = listOf(PageContentTool { content.body }),
        )
        session = newSession
        // No prior session means no page content in history yet, so include it with the question.
        stream(store) { newSession.send("$question\n\n---\nPage content:\n${content.body}") }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun stream(store: SummarizationStore, send: suspend () -> Flow<String>) {
        try {
            withTimeout(REQUEST_TIMEOUT) {
                send()
                    .mapToRichDocument(pageTitle = "", dispatcher = dispatcher)
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

    private suspend fun observeProvider(store: SummarizationStore) =
        llmProvider.fetchLlm.collect { store.dispatch(it) }

    private suspend fun needsShakeConsent(state: SummarizationState): Boolean =
        state is SummarizationState.Inert &&
            state.initializedWithShake &&
            !settings.getHasConsentedToShake().first()

    private companion object {
        val REQUEST_TIMEOUT = 60.seconds
    }
}
