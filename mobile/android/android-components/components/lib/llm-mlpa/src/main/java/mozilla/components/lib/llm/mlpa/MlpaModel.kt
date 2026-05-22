/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.mlpa

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mozilla.components.lib.llm.mlpa.service.AuthorizationToken
import mozilla.components.lib.llm.mlpa.service.ChatService
import mozilla.components.lib.llm.mlpa.service.ChatService.Request.Message
import mozilla.components.lib.llm.mlpa.service.ChatService.Request.ModelID

/**
 * ADK [Model] implementation that routes generation through MLPA's chat-completions endpoint.
 *
 * Single-turn only: tools, multimodal parts, and tool-call replies are not translated yet.
 */
class MlpaModel(
    override val name: String = ModelID.mozSummarization.value,
    private val chatService: ChatService,
    private val authorizationToken: AuthorizationToken,
) : Model {

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        val mlpaRequest = request.toMlpaRequest(modelId = ModelID(name), stream = stream)
        val accumulated = StringBuilder()

        chatService.completion(authorizationToken, mlpaRequest).collect { chunk ->
            accumulated.append(chunk)
            emit(
                LlmResponse(
                    content = Content(role = Role.MODEL, parts = listOf(Part(text = chunk))),
                    partial = true,
                ),
            )
        }

        emit(
            LlmResponse(
                content = Content(role = Role.MODEL, parts = listOf(Part(text = accumulated.toString()))),
                finishReason = FinishReason.STOP,
                partial = false,
            ),
        )
    }
}

private fun LlmRequest.toMlpaRequest(modelId: ModelID, stream: Boolean): ChatService.Request {
    val messages = buildList {
        config.systemInstruction?.flattenText()?.takeIf { it.isNotEmpty() }
            ?.let { add(Message.system(it)) }
        contents.forEach { content ->
            val text = content.flattenText()
            if (text.isEmpty()) return@forEach
            when (content.role) {
                Role.SYSTEM -> add(Message.system(text))
                else -> add(Message.user(text))
            }
        }
    }
    return ChatService.Request(model = modelId, messages = messages, stream = stream)
}

private fun Content.flattenText(): String =
    parts.mapNotNull { it.text }.joinToString(separator = "")
