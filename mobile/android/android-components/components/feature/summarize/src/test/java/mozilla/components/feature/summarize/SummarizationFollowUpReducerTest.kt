/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import mozilla.components.concept.llm.LlmProvider
import mozilla.components.ui.richtext.ir.RichDocument
import mozilla.components.ui.richtext.parsing.Parser
import org.junit.Test
import kotlin.test.assertIs

class SummarizationFollowUpReducerTest {
    private val info = LlmProvider.Info(nameRes = 0)
    private val document = RichDocument(listOf())

    @Test
    fun `ReadyForInput shows the suggestions state`() {
        val next = summarizationReducer(SummarizationState.Loading(info), ReadyForInput(info))
        assertIs<SummarizationState.AwaitingRequest>(next)
    }

    @Test
    fun `selecting a suggestion transitions to Thinking`() {
        val next = summarizationReducer(
            SummarizationState.AwaitingRequest(info),
            SuggestionSelected(SummarizationSuggestion.KeyPoints),
        )
        assertIs<SummarizationState.Thinking>(next)
    }

    @Test
    fun `submitting a typed request transitions to Thinking`() {
        val next = summarizationReducer(
            SummarizationState.AwaitingRequest(info),
            FollowUpSubmitted("why?"),
        )
        assertIs<SummarizationState.Thinking>(next)
    }

    @Test
    fun `receiving a document from Thinking transitions to Summarizing`() {
        val next = summarizationReducer(
            SummarizationState.Thinking(info),
            ReceivedParsedDocument(document),
        )
        assertIs<SummarizationState.Summarizing>(next)
    }

    @Test
    fun `completing from Summarizing transitions to Summarized`() {
        val next = summarizationReducer(
            SummarizationState.Summarizing(info, document),
            SummarizationCompleted,
        )
        assertIs<SummarizationState.Summarized>(next)
    }

    @Test
    fun `a new suggestion can be selected from the Summarized state`() {
        val next = summarizationReducer(
            SummarizationState.Summarized(info, document),
            SuggestionSelected(SummarizationSuggestion.SimpleTerms),
        )
        assertIs<SummarizationState.Thinking>(next)
    }

    @Test
    fun `the settings gear opens settings from Summarized and AwaitingRequest`() {
        assertIs<SummarizationState.Settings>(
            summarizationReducer(SummarizationState.Summarized(info, document), SettingsClicked),
        )
        assertIs<SummarizationState.Settings>(
            summarizationReducer(SummarizationState.AwaitingRequest(info), SettingsClicked),
        )
    }

    @Test
    fun `settings back returns to the appropriate state`() {
        assertIs<SummarizationState.AwaitingRequest>(
            summarizationReducer(SummarizationState.Settings(info, RichDocument(listOf())), SettingsBackClicked),
        )
        val summary = SummarizationState.Settings(info, Parser().parse("hi"))
        assertIs<SummarizationState.Summarized>(summarizationReducer(summary, SettingsBackClicked))
    }
}
