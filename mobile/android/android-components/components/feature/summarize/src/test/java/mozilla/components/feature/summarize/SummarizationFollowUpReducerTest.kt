/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import mozilla.components.concept.llm.LlmProvider
import mozilla.components.ui.richtext.ir.RichDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertIs

class SummarizationFollowUpReducerTest {
    private val info = LlmProvider.Info(nameRes = 0)
    private val summary = RichDocument(listOf())

    @Test
    fun `submitting a follow-up from Summarized transitions to RespondingToFollowUp`() {
        val next = summarizationReducer(
            SummarizationState.Summarized(info, summary),
            FollowUpSubmitted("why?"),
        )

        val state = assertIs<SummarizationState.RespondingToFollowUp>(next)
        assertEquals("why?", state.question)
        assertEquals(summary, state.summary)
    }

    @Test
    fun `received follow-up document updates the in-progress response`() {
        val document = RichDocument(listOf())
        val next = summarizationReducer(
            SummarizationState.RespondingToFollowUp(info, summary, "why?"),
            ReceivedFollowUpDocument(document),
        )

        assertEquals(document, assertIs<SummarizationState.RespondingToFollowUp>(next).response)
    }

    @Test
    fun `completing a follow-up transitions to FollowUpComplete`() {
        val next = summarizationReducer(
            SummarizationState.RespondingToFollowUp(info, summary, "why?"),
            FollowUpCompleted,
        )

        assertEquals("why?", assertIs<SummarizationState.FollowUpComplete>(next).question)
    }

    @Test
    fun `a follow-up can be asked again after completion`() {
        val next = summarizationReducer(
            SummarizationState.FollowUpComplete(info, summary, "why?", summary),
            FollowUpSubmitted("and then?"),
        )

        assertEquals("and then?", assertIs<SummarizationState.RespondingToFollowUp>(next).question)
    }

    @Test
    fun `the settings gear opens settings from FollowUpComplete`() {
        val next = summarizationReducer(
            SummarizationState.FollowUpComplete(info, summary, "why?", summary),
            SettingsClicked,
        )

        assertIs<SummarizationState.Settings>(next)
    }
}
