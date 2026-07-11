/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import mozilla.components.ui.richtext.ir.RichDocument

/**
 * Reduces the given [action] and current [state] into a new [SummarizationState].
 *
 * @param state The current [SummarizationState].
 * @param action The [SummarizationAction] to process.
 * @return The resulting [SummarizationState] after applying the action.
 */
fun summarizationReducer(state: SummarizationState, action: SummarizationAction) = when (action) {
    is ShakeConsentRequested -> SummarizationState.ShakeConsentRequired
    OffDeviceSummarizationShakeConsentAction.CancelClicked -> SummarizationState.Finished.Cancelled
    OffDeviceSummarizationShakeConsentAction.LearnMoreClicked -> SummarizationState.LearnMoreAboutShakeConsent
    OnDeviceSummarizationShakeConsentAction.LearnMoreClicked -> SummarizationState.LearnMoreAboutShakeConsent
    ErrorAction.ErrorDismissed -> SummarizationState.Finished.ErrorDismissed
    is SummarizationRequested -> SummarizationState.Loading(action.info)
    is ReadyForInput -> SummarizationState.AwaitingRequest(action.info)
    is ModelDownloadProgress -> SummarizationState.Downloading(action.bytesToDownload, action.bytesDownloaded)
    is SuggestionSelected -> state.toThinking()
    is FollowUpSubmitted -> state.toThinking()
    is ReceivedParsedDocument -> state.updateDocument(action.document)
    is SummarizationCompleted -> state.complete()
    is SummarizationFailed -> SummarizationState.Error(SummarizationError.SummarizationFailed(action.exception))
    is SettingsClicked -> when (state) {
        is SummarizationState.Summarized -> SummarizationState.Settings(state.info, state.document)
        is SummarizationState.AwaitingRequest -> SummarizationState.Settings(state.info, RichDocument(listOf()))
        else -> state
    }
    is SettingsBackClicked -> when (state) {
        is SummarizationState.Settings ->
            if (state.document.blocks.isEmpty()) {
                SummarizationState.AwaitingRequest(state.info)
            } else {
                SummarizationState.Summarized(state.info, state.document)
            }
        else -> state
    }
    else -> state
}

private fun SummarizationState.toThinking(): SummarizationState = when (this) {
    is SummarizationState.AwaitingRequest -> SummarizationState.Thinking(info)
    is SummarizationState.Summarized -> SummarizationState.Thinking(info)
    else -> this
}

private fun SummarizationState.complete(): SummarizationState {
    if (this !is SummarizationState.Summarizing) return this
    return SummarizationState.Summarized(info, document)
}

internal fun SummarizationState.updateDocument(document: RichDocument): SummarizationState {
    return when (this) {
        is SummarizationState.Thinking -> SummarizationState.Summarizing(info, document)
        is SummarizationState.Summarizing -> copy(document = document)
        else -> this
    }
}
