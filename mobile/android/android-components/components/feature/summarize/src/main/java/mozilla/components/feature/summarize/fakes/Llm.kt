/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize.fakes

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import mozilla.components.concept.llm.CloudLlmProvider
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.FinishReason
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.LlmResponse
import mozilla.components.concept.llm.LocalLlmProvider
import mozilla.components.concept.llm.Role
import mozilla.components.feature.summarize.R
import kotlin.time.Duration.Companion.seconds

/**
 * A fake implementation of [CloudLlmProvider] for use in tests and Compose previews.
 *
 * @property state The mutable state flow representing the current provider state.
 * Defaults to [CloudLlmProvider.State.Available].
 * @property preparedState The state to transition to while preparing
 */
data class FakeCloudProvider(
    override val state: MutableStateFlow<CloudLlmProvider.State> = MutableStateFlow(CloudLlmProvider.State.Available),
    val preparedState: CloudLlmProvider.State,
) : CloudLlmProvider {
    override val info = LlmProvider.Info(nameRes = R.string.mozac_summarize_fake_llm_name)

    override suspend fun prepare() {
        state.value = preparedState
    }
}

/**
 * A fake implementation of [LlmModel] for use in tests and Compose previews.
 *
 * Emits each item in [responses] sequentially as text deltas, with a 2-second delay between
 * each emission to simulate real LLM streaming latency, then a final aggregated response.
 *
 * @property responses values to emit.
 */
data class FakeLlmModel(
    val responses: List<String> = listOf(),
) : LlmModel {

    override val name: String = "fake"

    var lastRequest: LlmRequest? = null

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        lastRequest = request
        val accumulated = StringBuilder()
        for (response in responses) {
            accumulated.append(response)
            emit(LlmResponse(content = Content.text(Role.Model, response), partial = true))
            delay(2.seconds)
        }
        emit(
            LlmResponse(
                content = Content.text(Role.Model, accumulated.toString()),
                finishReason = FinishReason.Stop,
                partial = false,
            ),
        )
    }

    companion object {
        val successful get() = FakeLlmModel(
            listOf(
               "This is the article\n",
               "This is some content...\n",
               "This is some *bold* content.\n",
            ),
        )
    }
}

internal data class FakeLocalProvider(
    override val state: MutableStateFlow<LocalLlmProvider.State> = MutableStateFlow(
        LocalLlmProvider.State.ReadyToDownload,
    ),
    val model: LlmModel,
) : LocalLlmProvider {
    override val info = LlmProvider.Info(nameRes = R.string.mozac_summarize_fake_llm_name)

    override suspend fun downloadIfNeeded() {
        state.value = LocalLlmProvider.State.Downloading(TOTAL_SIZE, INITIAL_SIZE)
        delay(0.5.seconds)
        state.value = LocalLlmProvider.State.Downloading(TOTAL_SIZE, PARTIAL_SIZE)
        delay(1.seconds)
        state.value = LocalLlmProvider.State.Ready(model)
    }
}

private const val INITIAL_SIZE = 0L
private const val PARTIAL_SIZE = 5_000L
private const val TOTAL_SIZE = 10_000L
