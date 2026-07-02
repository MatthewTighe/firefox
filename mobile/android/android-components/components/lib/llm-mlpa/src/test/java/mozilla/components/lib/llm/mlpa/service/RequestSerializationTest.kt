/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.mlpa.service

import kotlinx.serialization.json.Json
import mozilla.components.concept.integrity.IntegrityToken
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.lib.llm.mlpa.mozSummarization
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSerializationTest {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `authentication service request gets serialized to json correctly`() {
        val request = AuthenticationService.Request(
            userId = UserId("my-user-id"),
            integrityToken = IntegrityToken(value = "my-integrity-token"),
            packageName = PackageName("my.package.name"),
        )

        assertEquals(
            "{" +
                "\"user_id\":\"my-user-id\"," +
                "\"integrity_token\":\"my-integrity-token\"," +
                "\"package_name\":\"my.package.name\"" +
                "}",
            json.encodeToString(request),
        )
    }

    @Test
    fun `chat service completion request gets serialized to json correctly`() {
        val request = ChatService.Request(
            model = LlmProvider.ModelID.mozSummarization,
            messages = listOf(
                ChatService.Request.Message.system("system prompt"),
                ChatService.Request.Message.user("hello"),
            ),
        )

        assertEquals(
            "{\"model\":\"moz-summarization\",\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"},{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true,\"temperature\":0.1,\"top_p\":0.01}",
            json.encodeToString(request),
        )
    }

    @Test
    fun `an assistant tool-call message omits content and serializes its tool calls`() {
        val message = ChatService.Request.Message.assistantToolCall(
            listOf(
                ChatService.Request.Message.ToolCall(
                    id = "call-1",
                    function = ChatService.Request.Message.ToolCall.Function(
                        name = "get_page_content",
                        arguments = "{}",
                    ),
                ),
            ),
        )

        assertEquals(
            "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"get_page_content\",\"arguments\":\"{}\"}}]}",
            json.encodeToString(message),
        )
    }
}
