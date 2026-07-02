/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.adk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.FunctionCall
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmResponse
import mozilla.components.concept.llm.LlmSession
import mozilla.components.concept.llm.LlmTool
import mozilla.components.concept.llm.Part
import mozilla.components.concept.llm.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdkLlmSessionTest {

    private class FakeModel(private val responses: List<String>) : LlmModel {
        override val name = "fake"
        var lastRequest: LlmRequest? = null

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            lastRequest = request
            val accumulated = StringBuilder()
            responses.forEach { chunk ->
                accumulated.append(chunk)
                emit(LlmResponse(content = Content.text(Role.Model, chunk), partial = true))
            }
            emit(
                LlmResponse(
                    content = Content.text(Role.Model, accumulated.toString()),
                    finishReason = FinishReason.Stop,
                    partial = false,
                ),
            )
        }
    }

    @Test
    fun `send streams the model's text deltas`() = runTest {
        val model = FakeModel(listOf("Hello ", "world"))
        val session = LlmSession.create(model = model)

        val output = session.send("hi").toList()

        assertEquals(listOf("Hello ", "world"), output)
    }

    @Test
    fun `send forwards the system prompt and user message to the model`() = runTest {
        val model = FakeModel(listOf("ok"))
        val session = LlmSession.create(model = model, systemPrompt = "be concise")

        session.send("summarize this").toList()

        assertEquals("be concise", model.lastRequest?.systemInstruction)
        assertEquals(
            "summarize this",
            model.lastRequest?.contents?.lastOrNull { it.role == Role.User }
                ?.parts?.firstNotNullOfOrNull { it.text },
        )
    }

    /**
     * Emits a function call until it sees a tool result in the history, then emits final text.
     */
    private class ToolCallingModel : LlmModel {
        override val name = "fake"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            val hasToolResult = request.contents.any { content ->
                content.parts.any { it.functionResponse != null }
            }
            if (hasToolResult) {
                emit(LlmResponse(content = Content.text(Role.Model, "Summary from the tool"), partial = true))
                emit(
                    LlmResponse(
                        content = Content.text(Role.Model, "Summary from the tool"),
                        finishReason = FinishReason.Stop,
                        partial = false,
                    ),
                )
            } else {
                emit(
                    LlmResponse(
                        content = Content(
                            role = Role.Model,
                            parts = listOf(
                                Part(functionCall = FunctionCall(name = "get_page_content", id = "call-1")),
                            ),
                        ),
                        finishReason = FinishReason.Stop,
                        partial = false,
                    ),
                )
            }
        }
    }

    private class RecordingTool : LlmTool {
        var invoked = false
        override val name = "get_page_content"
        override val description = "Returns the text content of the current page."
        override val parametersSchema: String? = null

        override suspend fun invoke(arguments: Map<String, Any?>): Map<String, Any?> {
            invoked = true
            return mapOf("content" to "the page text")
        }
    }

    @Test
    fun `send executes a model-requested tool and streams the final text`() = runTest {
        val tool = RecordingTool()
        val session = LlmSession.create(model = ToolCallingModel(), tools = listOf(tool))

        val output = session.send("summarize the page").toList()

        assertTrue(tool.invoked)
        assertEquals("Summary from the tool", output.joinToString(separator = ""))
    }
}
