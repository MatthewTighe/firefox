/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.adk

import com.google.adk.kt.models.LlmRequest as AdkLlmRequest
import com.google.adk.kt.models.LlmResponse as AdkLlmResponse
import com.google.adk.kt.models.Model as AdkModel
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FinishReason as AdkFinishReason
import com.google.adk.kt.types.FunctionCall as AdkFunctionCall
import com.google.adk.kt.types.FunctionResponse as AdkFunctionResponse
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Role as AdkRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.FunctionCall
import mozilla.components.concept.llm.FunctionResponse
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmResponse
import mozilla.components.concept.llm.LlmTool
import mozilla.components.concept.llm.Part
import mozilla.components.concept.llm.Role
import mozilla.components.concept.llm.ToolChoice

/**
 * Presents a backend-agnostic [LlmModel] to the ADK runtime as an ADK [AdkModel]. This is the
 * seam that lets ADK drive any Mozilla model backend without those backends depending on ADK.
 *
 * The [tools] registered on the session are forwarded to the model as declarations so backends
 * that support native tool calling (e.g. MLPA) can advertise them. The ADK runner remains the
 * component that actually executes tools and loops the results back.
 */
internal class ConceptModelAdapter(
    private val model: LlmModel,
    private val tools: List<LlmTool> = emptyList(),
) : AdkModel {
    override val name: String = model.name

    override fun generateContent(request: AdkLlmRequest, stream: Boolean): Flow<AdkLlmResponse> =
        model.generateContent(request.toConceptRequest(tools), stream).map { it.toAdkResponse() }
}

internal fun AdkLlmRequest.toConceptRequest(tools: List<LlmTool>): LlmRequest = LlmRequest(
    contents = contents.mapNotNull { it.toConceptContent() },
    systemInstruction = config.systemInstruction
        ?.parts
        ?.mapNotNull { it.text }
        ?.joinToString(separator = "")
        ?.takeIf { it.isNotEmpty() },
    tools = tools,
    toolChoice = if (tools.isEmpty()) ToolChoice.None else ToolChoice.Auto,
)

internal fun LlmResponse.toAdkResponse(): AdkLlmResponse = AdkLlmResponse(
    content = content?.toAdkContent(),
    finishReason = finishReason?.toAdkFinishReason(),
    partial = partial,
)

internal fun Content.toAdkContent(): AdkContent =
    AdkContent(role = role.toAdkRole(), parts = parts.map { it.toAdkPart() })

internal fun AdkContent.toConceptContent(): Content? {
    val converted = parts.mapNotNull { it.toConceptPart() }
    return if (converted.isEmpty()) null else Content(role.toConceptRole(), converted)
}

internal fun Part.toAdkPart(): AdkPart = AdkPart(
    text = text,
    functionCall = functionCall?.let { AdkFunctionCall(name = it.name, args = it.arguments, id = it.id) },
    functionResponse = functionResponse?.let {
        AdkFunctionResponse(name = it.name, response = it.response, id = it.id)
    },
)

internal fun AdkPart.toConceptPart(): Part? {
    val call = functionCall
    val response = functionResponse
    return when {
        text != null -> Part(text = text)
        call != null -> Part(functionCall = FunctionCall(call.name, call.args, call.id))
        response != null ->
            Part(functionResponse = FunctionResponse(response.name, response.response, response.id))
        else -> null
    }
}

internal fun Role.toAdkRole(): String = when (this) {
    Role.User -> AdkRole.USER
    Role.Model -> AdkRole.MODEL
    Role.System -> AdkRole.SYSTEM
}

internal fun String?.toConceptRole(): Role = when (this) {
    AdkRole.MODEL -> Role.Model
    AdkRole.SYSTEM -> Role.System
    else -> Role.User
}

internal fun FinishReason.toAdkFinishReason(): AdkFinishReason = when (this) {
    FinishReason.Stop -> AdkFinishReason.STOP
    FinishReason.MaxTokens -> AdkFinishReason.MAX_TOKENS
    FinishReason.Other -> AdkFinishReason.OTHER
}
