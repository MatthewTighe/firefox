/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.gemini.nano

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmResponse
import mozilla.components.concept.llm.Role
import mozilla.components.support.base.log.logger.Logger

private const val MODEL_NAME = "gemini-nano"

/**
 * An [LlmModel] that uses local, on-device capabilities provided by Gemini Nano to handle
 * inference. Single-turn text only: the request's system instruction and content are flattened
 * into a single prompt, since Gemini Nano does not distinguish message roles or support tools.
 */
internal class GeminiNanoLlm(
    private val buildModel: () -> GenerativeModel = { Generation.getClient() },
    private val logger: (String) -> Unit = { message -> Logger("mozac/GeminiNanoLlm").info(message) },
) : LlmModel {

    override val name: String = MODEL_NAME

    private val model by lazy {
        buildModel()
    }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        streamResponses(request)
    }

    private suspend fun FlowCollector<LlmResponse>.streamResponses(request: LlmRequest) = try {
        // consume replies from the model until it provides a finish reason
        logger("Beginning model response stream")
        val accumulated = StringBuilder()
        model.generateContentStream(request.flatten()).onEach { response ->
            val text = response.candidates[0].text
            accumulated.append(text)
            emit(LlmResponse(content = Content.text(Role.Model, text), partial = true))
        }.first {
            val finishReason = it.candidates[0].finishReason
            (finishReason != null).also {
                logger("Model stream completed with: $finishReason")
            }
        }
        emit(
            LlmResponse(
                content = Content.text(Role.Model, accumulated.toString()),
                finishReason = FinishReason.Stop,
                partial = false,
            ),
        )
    } catch (e: GenAiException) {
        logger("Gemini Nano inference failed: ${e.message}")
        throw GeminiNanoInferenceError(e)
    }
}

private fun LlmRequest.flatten(): String = buildList {
    systemInstruction?.takeIf { it.isNotEmpty() }?.let { add(it) }
    contents.forEach { content ->
        content.parts.mapNotNull { it.text }.joinToString(separator = "")
            .takeIf { it.isNotEmpty() }
            ?.let { add(it) }
    }
}.joinToString(separator = "\n\n")
