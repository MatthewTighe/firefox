/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize.ext

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import mozilla.components.concept.llm.CloudLlmProvider
import mozilla.components.concept.llm.Llm
import mozilla.components.concept.llm.LlmProvider
import mozilla.components.concept.llm.LocalLlmProvider
import mozilla.components.concept.llm.ModelDownloadFailed
import mozilla.components.concept.llm.ModelUnavailable
import mozilla.components.feature.summarize.LlmProviderAction
import mozilla.components.feature.summarize.ModelDownloadProgress
import mozilla.components.feature.summarize.SummarizationAction
import mozilla.components.feature.summarize.SummarizationFailed
import mozilla.components.feature.summarize.SummarizationRequested

/**
 * Observes an [LlmProvider] (cloud or local) and maps its lifecycle to [SummarizationAction]s.
 */
internal val LlmProvider.fetchLlm: Flow<SummarizationAction> get() = when (this) {
    is CloudLlmProvider -> flow {
        emit(SummarizationRequested(info))
        emitAll(state.map { it.action })
    }
    is LocalLlmProvider -> flow {
        emit(SummarizationRequested(info))
        emitAll(state.map { it.action })
    }
}

internal val CloudLlmProvider.State.action: SummarizationAction get() = when (this) {
    CloudLlmProvider.State.Available -> LlmProviderAction.ProviderAvailable
    is CloudLlmProvider.State.Ready -> LlmProviderAction.ProviderInitialized(model)
    is CloudLlmProvider.State.Unavailable -> SummarizationFailed(exception)
}

internal val LocalLlmProvider.State.action: SummarizationAction get() = when (this) {
    is LocalLlmProvider.State.Ready -> LlmProviderAction.ProviderInitialized(model)
    LocalLlmProvider.State.Idle,
    LocalLlmProvider.State.ReadyToDownload,
    -> LlmProviderAction.ProviderNeedsDownload
    is LocalLlmProvider.State.Downloading ->
        ModelDownloadProgress(bytesToDownload.toFloat(), bytesDownloaded.toFloat())
    LocalLlmProvider.State.Unavailable ->
        SummarizationFailed(object : Llm.Exception("On-device model is unavailable"), ModelUnavailable {})
    LocalLlmProvider.State.Failed ->
        SummarizationFailed(object : Llm.Exception("On-device model download failed"), ModelDownloadFailed {})
}
