/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.mlpa

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmTool
import mozilla.components.concept.llm.Role
import mozilla.components.concept.llm.ToolChoice
import mozilla.components.lib.llm.mlpa.fakes.failureChatService
import mozilla.components.lib.llm.mlpa.fakes.successChatService
import mozilla.components.lib.llm.mlpa.service.AuthorizationToken
import mozilla.components.lib.llm.mlpa.service.ChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlpaModelTest {
    private fun request(userText: String, systemInstruction: String? = null) = LlmRequest(
        contents = listOf(Content.text(Role.User, userText)),
        systemInstruction = systemInstruction,
    )

    @Test
    fun `GIVEN a successful response from the mlpa client WHEN generating THEN I get the streamed text`() = runTest {
        var expectedToken: AuthorizationToken? = null
        val model = MlpaModel(
            chatService = { token, request ->
                expectedToken = token
                successChatService.completion(token, request)
            },
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )

        val responses = model.generateContent(request("This is my prompt")).toList()
        val finalResponse = responses.last()

        assertEquals(AuthorizationToken.Integrity("my-test-token").value, expectedToken?.value)
        assertEquals("Hello World!", finalResponse.content?.parts?.firstNotNullOfOrNull { it.text })
        assertEquals(FinishReason.Stop, finalResponse.finishReason)
        assertTrue(responses.dropLast(1).all { it.partial })
    }

    @Test
    fun `GIVEN a failure response from the mlpa client WHEN generating THEN the flow throws`() = runTest {
        var threw = false

        val model = MlpaModel(
            chatService = failureChatService,
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )

        model.generateContent(request("This is my prompt"))
            .catch { threw = true }
            .toList()

        assertTrue(threw)
    }

    @Test
    fun `that we attach the system instruction to the request if present`() = runTest {
        var actualRequest: ChatService.Request? = null
        val model = MlpaModel(
            chatService = { token, request ->
                actualRequest = request
                successChatService.completion(token, request)
            },
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )

        model.generateContent(request("user prompt", "system prompt")).toList()

        val expected = listOf(
            ChatService.Request.Message.system("system prompt"),
            ChatService.Request.Message.user("user prompt"),
        )

        assertEquals(expected, actualRequest?.messages)
    }

    @Test
    fun `that the system instruction is not attached to the request if null`() = runTest {
        var actualRequest: ChatService.Request? = null
        val model = MlpaModel(
            chatService = { token, request ->
                actualRequest = request
                successChatService.completion(token, request)
            },
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )

        model.generateContent(request("user prompt", null)).toList()

        val expected = listOf(
            ChatService.Request.Message.user("user prompt"),
        )

        assertEquals(expected, actualRequest?.messages)
    }

    @Test
    fun `tools and tool choice are forwarded to the MLPA request`() = runTest {
        var actualRequest: ChatService.Request? = null
        val model = MlpaModel(
            chatService = { token, request ->
                actualRequest = request
                successChatService.completion(token, request)
            },
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )
        val tool = object : LlmTool {
            override val name = "get_page_content"
            override val description = "Reads the current page."
            override val parametersSchema: String? = null
            override suspend fun invoke(arguments: Map<String, Any?>) = emptyMap<String, Any?>()
        }

        model.generateContent(
            LlmRequest(
                contents = listOf(Content.text(Role.User, "summarize")),
                tools = listOf(tool),
                toolChoice = ToolChoice.Auto,
            ),
        ).toList()

        assertEquals("get_page_content", actualRequest?.tools?.single()?.function?.name)
        assertEquals("auto", actualRequest?.toolChoice)
    }

    @Test
    fun `tool call responses surface as function-call parts`() = runTest {
        val toolCallService = ChatService { _, _ ->
            listOf<ChatService.TurnEvent>(
                ChatService.TurnEvent.ToolCalls(
                    listOf(ChatService.ToolCallRecord("call-1", "get_page_content", "{}")),
                ),
            ).asFlow()
        }
        val model = MlpaModel(
            chatService = toolCallService,
            authorizationToken = AuthorizationToken.Integrity("my-test-token"),
            modelID = LlmProvider.ModelID.mozSummarization,
        )

        val responses = model.generateContent(request("summarize")).toList()
        val call = responses.first().content?.parts?.firstNotNullOfOrNull { it.functionCall }

        assertEquals("get_page_content", call?.name)
        assertEquals("call-1", call?.id)
    }
}
