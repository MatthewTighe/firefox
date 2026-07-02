/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.mlpa.service.ext

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mozilla.components.concept.fetch.Response
import mozilla.components.concept.llm.Llm
import mozilla.components.lib.llm.mlpa.service.BudgetExceeded
import mozilla.components.lib.llm.mlpa.service.ChatService
import mozilla.components.lib.llm.mlpa.service.ChatService.TurnEvent
import mozilla.components.lib.llm.mlpa.service.RateLimitResponseParseError
import mozilla.components.lib.llm.mlpa.service.RateLimited
import mozilla.components.lib.llm.mlpa.service.ServerError

private const val DATA_PREFIX = "data: "
private const val END_OF_STREAM_MARKER = "[DONE]"

/**
 * A [Flow] of [TurnEvent]s parsed from a server-sent events (SSE) stream in this [Response].
 *
 * Text deltas are emitted as [TurnEvent.TextDelta] as they arrive. Tool-call deltas are
 * accumulated by index across the stream and emitted as a single [TurnEvent.ToolCalls] once the
 * stream ends. A given stream carries either text or tool calls, never both.
 */
internal fun Response.turnEventFlow(retryAfter: Long?): Flow<TurnEvent> = flow {
    val toolCallAccum = sortedMapOf<Int, PartialToolCall>()

    lineFlow
        .filterNot { it.isEmpty() || it.contains(END_OF_STREAM_MARKER) }
        .map { it.drop(DATA_PREFIX.length) }
        .events(retryAfter)
        .collect { event ->
            for (choice in event.choices) {
                choice.delta.content?.takeIf { it.isNotEmpty() }?.let { emit(TurnEvent.TextDelta(it)) }
                for (toolCall in choice.delta.toolCalls) {
                    val partial = toolCallAccum.getOrPut(toolCall.index) { PartialToolCall() }
                    toolCall.id?.let { partial.id = it }
                    toolCall.function?.name?.let { partial.name = it }
                    toolCall.function?.arguments?.let { partial.arguments.append(it) }
                }
            }
        }

    if (toolCallAccum.isNotEmpty()) {
        emit(
            TurnEvent.ToolCalls(
                toolCallAccum.values.map {
                    ChatService.ToolCallRecord(it.id, it.name, it.arguments.toString())
                },
            ),
        )
    }
}

private class PartialToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)

private val Response.lineFlow get() = channelFlow {
    body.useBufferedReader { reader ->
        reader.lineSequence().forEach { line ->
            trySend(line)
        }
    }
}

private fun Flow<String>.events(retryAfter: Long?): Flow<Event> {
    val json = Json {
        ignoreUnknownKeys = true
    }
    return map { line ->
        try {
            json.decodeFromString(line)
        } catch (e: SerializationException) {
            throw json.rateLimitDetailedError(line, retryAfter)
        }
    }
}

internal fun Json.rateLimitDetailedError(serialized: String, retryAfter: Long?): Llm.Exception = try {
    val rateLimitStatus = 429
    when (this.decodeFromString<ChatService.ResponseErrorCode>(serialized).error) {
        1 -> BudgetExceeded(retryAfter)
        2 -> RateLimited(retryAfter)
        else -> ServerError(rateLimitStatus)
    }
} catch (e: SerializationException) {
    RateLimitResponseParseError(e)
}

@Serializable
private data class Event(
    val id: String = "",
    val created: Long = 0L,
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val delta: Delta = Delta(),
    ) {
        @Serializable
        data class Delta(
            val content: String? = null,
            @SerialName("tool_calls") val toolCalls: List<ToolCallDelta> = emptyList(),
        )
    }
}

@Serializable
private data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionDelta? = null,
) {
    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null,
    )
}
