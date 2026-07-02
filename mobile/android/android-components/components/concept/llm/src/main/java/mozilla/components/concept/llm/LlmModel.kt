/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.llm

import kotlinx.coroutines.flow.Flow

/**
 * Controls whether the model may, must, or must not call a tool on a given turn.
 * Backends that do not support tool calling ignore this.
 */
enum class ToolChoice {
    /** The model decides whether to call a tool. */
    Auto,

    /** The model must call a tool this turn. */
    Required,

    /** The model must not call any tool this turn. */
    None,
}

/**
 * Why the model stopped generating a response.
 */
enum class FinishReason {
    /** The model produced a complete response. */
    Stop,

    /** Generation was truncated because the output token limit was reached. */
    MaxTokens,

    /** The model stopped for a reason not otherwise categorised. */
    Other,
}

/**
 * A single inference request delivered to an [LlmModel].
 *
 * @property contents The conversation history, oldest first.
 * @property systemInstruction An optional system-level instruction shaping model behavior.
 * @property tools The tools available to the model this turn. Empty means no tool calling.
 * @property toolChoice Whether the model may, must, or must not call a tool this turn.
 */
data class LlmRequest(
    val contents: List<Content>,
    val systemInstruction: String? = null,
    val tools: List<LlmTool> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
)

/**
 * A single event emitted by an [LlmModel] while responding.
 *
 * Text responses are streamed as a sequence of [partial] events carrying [Content] text
 * deltas, optionally followed by a non-partial event. Tool-call responses carry [Content]
 * whose parts include [FunctionCall]s.
 *
 * @property content The content produced this event, if any.
 * @property finishReason Why generation stopped, on the terminating event.
 * @property partial `true` while the response is still streaming; `false` on the final event.
 */
data class LlmResponse(
    val content: Content? = null,
    val finishReason: FinishReason? = null,
    val partial: Boolean = false,
)

/**
 * A specific large language model that can perform inference.
 *
 * This is the backend service-provider interface: MLPA, Gemini Nano, and any future
 * backend implement it. It is intentionally free of any dependency on a particular agent
 * framework so the machinery driving it (sessions, tool loops) can be replaced without
 * touching backends or consumers.
 */
interface LlmModel {
    /**
     * A non-user-facing identifier for the model, used for telemetry and logging.
     */
    val name: String

    /**
     * Performs inference for [request].
     *
     * @param request The conversation and tools to run inference over.
     * @param stream When `true`, emit partial responses as they are produced.
     * @return A [Flow] of [LlmResponse] events.
     */
    fun generateContent(request: LlmRequest, stream: Boolean = false): Flow<LlmResponse>
}
