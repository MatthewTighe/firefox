/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.llm

/**
 * The author of a piece of [Content] in a conversation. Mirrors the roles used by
 * emerging agent standards: end-user input, model output, and system instructions.
 */
enum class Role {
    User,
    Model,
    System,
}

/**
 * A single request the model made to invoke a tool.
 *
 * @property name The name of the tool the model wants to call.
 * @property arguments The arguments for the call, keyed by parameter name.
 * @property id Correlates this call with its [FunctionResponse]. `null` when the backend
 *  does not supply correlation identifiers.
 */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val id: String? = null,
)

/**
 * The result of executing a [FunctionCall], fed back to the model.
 *
 * @property name The name of the tool that produced this result.
 * @property response The tool's result, keyed for the model to consume.
 * @property id Correlates this response with its originating [FunctionCall].
 */
data class FunctionResponse(
    val name: String,
    val response: Map<String, Any?> = emptyMap(),
    val id: String? = null,
)

/**
 * A single fragment of a [Content]. Exactly one of the properties is expected to be set.
 *
 * @property text Natural-language text.
 * @property functionCall A tool invocation requested by the model.
 * @property functionResponse The result of a previously requested tool invocation.
 */
data class Part(
    val text: String? = null,
    val functionCall: FunctionCall? = null,
    val functionResponse: FunctionResponse? = null,
) {
    companion object {
        /** Convenience factory for a text [Part]. */
        fun text(text: String) = Part(text = text)
    }
}

/**
 * A single turn in a conversation, attributed to a [Role] and composed of one or more [Part]s.
 *
 * @property role The author of this turn.
 * @property parts The fragments that make up this turn.
 */
data class Content(
    val role: Role,
    val parts: List<Part>,
) {
    companion object {
        /** Convenience factory for a single-text-part [Content]. */
        fun text(role: Role, text: String) = Content(role, listOf(Part.text(text)))
    }
}
