/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.llm.CloudLlmProvider
import mozilla.components.concept.llm.Llm
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.Role
import mozilla.components.feature.summarize.content.Content
import mozilla.components.feature.summarize.content.PageMetadata
import mozilla.components.feature.summarize.ext.defaultInstructions
import mozilla.components.feature.summarize.ext.keyPointsInstructions
import mozilla.components.feature.summarize.ext.recipeInstructions
import mozilla.components.feature.summarize.fakes.FakeCloudProvider
import mozilla.components.feature.summarize.fakes.FakeLlmModel
import mozilla.components.feature.summarize.settings.SummarizationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SummarizationStoreTest {

    private val reportedErrors = mutableListOf<Throwable>()
    private val errorReporter = ErrorReporter { _, exception -> reportedErrors.add(exception) }
    private val noopReporter = ErrorReporter { _, _ -> }

    private val body = "this is expected content."

    @Before
    fun setUp() {
        reportedErrors.clear()
    }

    private fun content(
        structuredDataTypes: List<String> = listOf("Article"),
        language: String = "en",
    ) = Content(PageMetadata(structuredDataTypes, 0, language, pageTitle = "Article Headline"), body)

    private fun store(
        model: FakeLlmModel = FakeLlmModel.successful,
        provider: CloudLlmProvider = FakeCloudProvider(preparedState = CloudLlmProvider.State.Ready(model)),
        settings: SummarizationSettings = SummarizationSettings.inMemory(hasConsentedToShake = true),
        content: Content = content(),
        contentResult: Result<Content> = Result.success(content),
        reporter: ErrorReporter = noopReporter,
        initializedWithShake: Boolean = false,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SummarizationStore(
        initialState = SummarizationState.Inert(initializedWithShake),
        reducer = ::summarizationReducer,
        middleware = listOf(
            SummarizationMiddleware(
                settings = settings,
                llmProvider = provider,
                contentProvider = { contentResult },
                errorReporter = reporter,
                scope = scope,
                dispatcher = StandardTestDispatcher(scheduler),
            ),
        ),
    )

    @Test
    fun `appearing does not summarize automatically and shows suggestions`() = runTest {
        val model = FakeLlmModel.successful
        val store = store(model = model, scheduler = testScheduler, scope = backgroundScope)
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)

        assertTrue(states.any { it is SummarizationState.AwaitingRequest })
        assertTrue(states.none { it is SummarizationState.Summarizing })
        assertNull("no request should be sent before a suggestion is picked", model.lastRequest)
    }

    @Test
    fun `declining shake consent finishes the flow`() = runTest {
        val store = store(
            settings = SummarizationSettings.inMemory(hasConsentedToShake = false),
            initializedWithShake = true,
            scheduler = testScheduler,
            scope = backgroundScope,
        )

        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }
        testScheduler.advanceTimeBy(1.seconds)

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(1.seconds)

        store.dispatch(OffDeviceSummarizationShakeConsentAction.CancelClicked)
        testScheduler.advanceTimeBy(1.seconds)

        assertEquals(SummarizationState.ShakeConsentRequired, states[1])
        assertEquals(SummarizationState.Finished.Cancelled, states.last())
    }

    @Test
    fun `selecting the summarize suggestion sends default instructions and streams to a summary`() = runTest {
        val model = FakeLlmModel.successful
        val store = store(model = model, scheduler = testScheduler, scope = backgroundScope)
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)
        store.dispatch(SuggestionSelected(SummarizationSuggestion.Summarize))
        testScheduler.advanceTimeBy(15.seconds)

        assertTrue(states.any { it is SummarizationState.Thinking })
        assertTrue(states.any { it is SummarizationState.Summarizing })
        assertTrue(states.last() is SummarizationState.Summarized)
        assertEquals(defaultInstructions("en"), model.lastRequest?.systemInstruction)
        assertEquals(body, model.lastRequest?.userText())
    }

    @Test
    fun `selecting the key points suggestion uses key points instructions`() = runTest {
        val model = FakeLlmModel.successful
        val store = store(model = model, scheduler = testScheduler, scope = backgroundScope)
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)
        store.dispatch(SuggestionSelected(SummarizationSuggestion.KeyPoints))
        testScheduler.advanceTimeBy(15.seconds)

        assertEquals(keyPointsInstructions("en"), model.lastRequest?.systemInstruction)
    }

    @Test
    fun `summarize suggestion on a recipe page uses recipe instructions`() = runTest {
        val model = FakeLlmModel.successful
        val store = store(
            model = model,
            content = content(structuredDataTypes = listOf("Recipe"), language = "de"),
            scheduler = testScheduler,
            scope = backgroundScope,
        )
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)
        store.dispatch(SuggestionSelected(SummarizationSuggestion.Summarize))
        testScheduler.advanceTimeBy(15.seconds)

        assertEquals(recipeInstructions("de"), model.lastRequest?.systemInstruction)
    }

    @Test
    fun `a typed request after a suggestion reuses the session`() = runTest {
        val model = FakeLlmModel.successful
        val store = store(model = model, scheduler = testScheduler, scope = backgroundScope)
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)
        store.dispatch(SuggestionSelected(SummarizationSuggestion.Summarize))
        testScheduler.advanceTimeBy(15.seconds)
        store.dispatch(FollowUpSubmitted("why does it matter?"))
        testScheduler.advanceTimeBy(15.seconds)

        assertTrue(states.last() is SummarizationState.Summarized)
        assertEquals("why does it matter?", model.lastRequest?.userText())
    }

    @Test
    fun `content extraction failure surfaces an error`() = runTest {
        val failure = NullPointerException("extractor failed")
        val store = store(
            contentResult = Result.failure(failure),
            reporter = errorReporter,
            scheduler = testScheduler,
            scope = backgroundScope,
        )
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.advanceTimeBy(15.seconds)

        assertEquals(
            SummarizationState.Error(SummarizationError.SummarizationFailed(failure)),
            states.last(),
        )
        assertEquals(listOf<Throwable>(failure), reportedErrors)
    }

    @Test
    fun `provider unavailable surfaces an error`() = runTest {
        val exception = Llm.Exception("cloud not clouding")
        val provider = FakeCloudProvider(preparedState = CloudLlmProvider.State.Unavailable(exception))
        val store = store(provider = provider, reporter = errorReporter, scheduler = testScheduler, scope = backgroundScope)
        val states = mutableListOf<SummarizationState>()
        backgroundScope.launch { store.stateFlow.toList(states) }

        store.dispatch(ViewAppeared)
        testScheduler.runCurrent()

        assertEquals(
            SummarizationState.Error(SummarizationError.SummarizationFailed(exception)),
            states.last(),
        )
    }

    private fun LlmRequest.userText() =
        contents.lastOrNull { it.role == Role.User }?.parts?.firstNotNullOfOrNull { it.text }
}
