/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.summarization

import mozilla.components.concept.llm.Llm
import mozilla.components.lib.llm.mlpa.service.ChatServiceError
import mozilla.components.lib.llm.mlpa.service.IntegrityHandshakeFailure
import mozilla.components.lib.llm.mlpa.service.VerificationServiceFailed

/**
 * The result of looking up an error code for a throwable.
 */
sealed class ErrorLookupResult {
    abstract val code: Int

    /** The throwable was a recognized [Llm.Exception] subtype. */
    data class Known(val exception: Llm.Exception, override val code: Int) : ErrorLookupResult()

    /** The throwable was not a recognized [Llm.Exception] subtype. */
    data class Unknown(val throwable: Throwable, override val code: Int = FALLBACK_CODE) : ErrorLookupResult()

    companion object {
        const val FALLBACK_CODE = 9999
    }
}

/**
 * Maps a [Throwable] to a stable numeric code for UI display and telemetry.
 *
 * Known [Llm.Exception] subtypes resolve to their assigned code; everything else
 * resolves to [ErrorLookupResult.FALLBACK_CODE]. Codes are app-level concerns and
 * intentionally live outside of [Llm.Exception] itself.
 */
object ErrorCodeLookup {
    fun lookup(throwable: Throwable): ErrorLookupResult = when (throwable) {
        is IntegrityHandshakeFailure -> ErrorLookupResult.Known(throwable, 1002)
        is VerificationServiceFailed -> ErrorLookupResult.Known(throwable, 1003)
        is ChatServiceError.InvalidToken -> ErrorLookupResult.Known(throwable, 1004)
        is ChatServiceError.UserBlocked -> ErrorLookupResult.Known(throwable, 1005)
        is ChatServiceError.RequestTooLarge -> ErrorLookupResult.Known(throwable, 1006)
        is ChatServiceError.BudgetExceeded -> ErrorLookupResult.Known(throwable, 1007)
        is ChatServiceError.RateLimited -> ErrorLookupResult.Known(throwable, 1008)
        is ChatServiceError.UpstreamError -> ErrorLookupResult.Known(throwable, 1009)
        is ChatServiceError.ServerError -> ErrorLookupResult.Known(throwable, 1010)
        is ChatServiceError.ChatNetworkError -> ErrorLookupResult.Known(throwable, 1011)
        is ChatServiceError.ResponseParseError -> ErrorLookupResult.Known(throwable, 1012)
        is ChatServiceError.RateLimitResponseParseError -> ErrorLookupResult.Known(throwable, 1013)
        is ChatServiceError.UpstreamResponseParseError -> ErrorLookupResult.Known(throwable, 1014)
        is ChatServiceError.VerificationResponseParseError -> ErrorLookupResult.Known(throwable, 1017)
        is ChatServiceError.VerificationNetworkError -> ErrorLookupResult.Known(throwable, 1018)
        is Llm.Exception -> ErrorLookupResult.Known(throwable, ErrorLookupResult.FALLBACK_CODE)
        else -> ErrorLookupResult.Unknown(throwable)
    }
}
