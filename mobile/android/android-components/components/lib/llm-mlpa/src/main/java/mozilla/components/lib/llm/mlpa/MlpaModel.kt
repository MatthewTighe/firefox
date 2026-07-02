/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.mlpa

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.FunctionCall
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmProvider.ModelID
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmResponse
import mozilla.components.concept.llm.LlmTool
import mozilla.components.concept.llm.Part
import mozilla.components.concept.llm.Role
import mozilla.components.concept.llm.ToolChoice
import mozilla.components.lib.llm.mlpa.service.AuthorizationToken
import mozilla.components.lib.llm.mlpa.service.ChatService
import mozilla.components.lib.llm.mlpa.service.ChatService.Request
import mozilla.components.lib.llm.mlpa.service.ChatService.Request.Message

/**
 * [LlmModel] that routes inference through MLPA's chat-completions endpoint, including
 * OpenAI-style tool calling. Text responses stream as deltas; tool-call responses surface as a
 * single response whose content carries [FunctionCall] parts for the ADK runner to execute.
 *
 * @property chatService The service used to request completions.
 * @property authorizationToken The token authorizing requests.
 * @property modelID The MLPA model this instance targets.
 */
internal class MlpaModel(
    val chatService: ChatService,
    val authorizationToken: AuthorizationToken,
    val modelID: ModelID,
) : LlmModel {
    override val name: String = modelID.value

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        val accumulated = StringBuilder()
        var sawText = false
        chatService.completion(authorizationToken, request.toRequest(modelID, stream)).collect { event ->
            when (event) {
                is ChatService.TurnEvent.TextDelta -> {
                    sawText = true
                    accumulated.append(event.text)
                    emit(LlmResponse(content = Content.text(Role.Model, event.text), partial = true))
                }
                is ChatService.TurnEvent.ToolCalls -> emit(
                    LlmResponse(
                        content = Content(
                            role = Role.Model,
                            parts = event.calls.map {
                                Part(
                                    functionCall = FunctionCall(
                                        name = it.toolName,
                                        arguments = parseArguments(it.arguments),
                                        id = it.id,
                                    ),
                                )
                            },
                        ),
                        finishReason = FinishReason.Stop,
                        partial = false,
                    ),
                )
            }
        }
        if (sawText) {
            emit(
                LlmResponse(
                    content = Content.text(Role.Model, accumulated.toString()),
                    finishReason = FinishReason.Stop,
                    partial = false,
                ),
            )
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

internal fun LlmRequest.toRequest(model: ModelID, stream: Boolean) = Request(
    model = model,
    messages = buildList {
        systemInstruction?.takeIf { it.isNotEmpty() }?.let { add(Message.system(it)) }
        contents.forEach { addAll(it.toMlpaMessages()) }
    },
    stream = stream,
    tools = tools.takeIf { it.isNotEmpty() }?.map { it.toMlpaTool() },
    toolChoice = if (tools.isEmpty()) null else toolChoice.toMlpaToolChoice(),
)

private fun Content.toMlpaMessages(): List<Message> {
    val functionCalls = parts.mapNotNull { it.functionCall }
    val functionResponses = parts.mapNotNull { it.functionResponse }
    val text = parts.mapNotNull { it.text }.joinToString(separator = "")
    return when {
        functionCalls.isNotEmpty() -> listOf(
            Message.assistantToolCall(
                functionCalls.map {
                    Message.ToolCall(
                        id = it.id.orEmpty(),
                        function = Message.ToolCall.Function(it.name, it.arguments.toJsonString()),
                    )
                },
            ),
        )
        functionResponses.isNotEmpty() -> functionResponses.map {
            Message.tool(toolCallId = it.id.orEmpty(), content = it.response.toJsonString())
        }
        text.isEmpty() -> emptyList()
        role == Role.System -> listOf(Message.system(text))
        role == Role.Model -> listOf(Message.assistant(text))
        else -> listOf(Message.user(text))
    }
}

private fun LlmTool.toMlpaTool(): Request.Tool = Request.Tool(
    function = Request.Tool.Function(
        name = name,
        description = description,
        parameters = parametersSchema?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: emptyObjectSchema,
    ),
)

private val emptyObjectSchema: JsonElement = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(emptyMap()))
}

private fun ToolChoice.toMlpaToolChoice(): String = when (this) {
    ToolChoice.Auto -> "auto"
    ToolChoice.Required -> "required"
    ToolChoice.None -> "none"
}

private fun parseArguments(raw: String): Map<String, Any?> = runCatching {
    json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) -> value.toKotlinValue() }
}.getOrDefault(emptyMap())

private fun JsonElement.toKotlinValue(): Any? = when (this) {
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    else -> toString()
}

private fun Map<String, Any?>.toJsonString(): String = buildJsonObject {
    forEach { (key, value) -> put(key, value?.toString() ?: "") }
}.toString()
