/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.summarization

import mozilla.components.concept.llm.Llm
import mozilla.components.lib.llm.mlpa.service.BudgetExceeded
import mozilla.components.lib.llm.mlpa.service.ChatNetworkError
import mozilla.components.lib.llm.mlpa.service.IntegrityHandshakeFailure
import mozilla.components.lib.llm.mlpa.service.InvalidToken
import mozilla.components.lib.llm.mlpa.service.MlpaError
import mozilla.components.lib.llm.mlpa.service.RateLimitResponseParseError
import mozilla.components.lib.llm.mlpa.service.RateLimited
import mozilla.components.lib.llm.mlpa.service.RequestTooLarge
import mozilla.components.lib.llm.mlpa.service.ResponseParseError
import mozilla.components.lib.llm.mlpa.service.ServerError
import mozilla.components.lib.llm.mlpa.service.UpstreamError
import mozilla.components.lib.llm.mlpa.service.UpstreamResponseParseError
import mozilla.components.lib.llm.mlpa.service.UserBlocked
import mozilla.components.lib.llm.mlpa.service.VerificationNetworkError
import mozilla.components.lib.llm.mlpa.service.VerificationResponseParseError
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
 * Branching is tiered: concrete impl subtypes first (most specific), then the impl marker
 * for unenumerated impl errors, then a global fallback. Code ranges are reserved per impl
 * module so reports tell us which provider an error came from at a glance.
 *
 * Code ranges:
 *  - 1000-1099: MLPA (lib/llm-mlpa)
 *  - 1100-1199: reserved for the next cloud provider
 *  - 9999:      global fallback for unrecognized errors
 */
object ErrorCodeLookup {
    fun lookup(throwable: Throwable): ErrorLookupResult = when (throwable) {
        // MLPA — codes 1000-1099
        is IntegrityHandshakeFailure -> ErrorLookupResult.Known(throwable, 1002)
        is VerificationServiceFailed -> ErrorLookupResult.Known(throwable, 1003)
        is InvalidToken -> ErrorLookupResult.Known(throwable, 1004)
        is UserBlocked -> ErrorLookupResult.Known(throwable, 1005)
        is RequestTooLarge -> ErrorLookupResult.Known(throwable, 1006)
        is BudgetExceeded -> ErrorLookupResult.Known(throwable, 1007)
        is RateLimited -> ErrorLookupResult.Known(throwable, 1008)
        is UpstreamError -> ErrorLookupResult.Known(throwable, 1009)
        is ServerError -> ErrorLookupResult.Known(throwable, 1010)
        is ChatNetworkError -> ErrorLookupResult.Known(throwable, 1011)
        is ResponseParseError -> ErrorLookupResult.Known(throwable, 1012)
        is RateLimitResponseParseError -> ErrorLookupResult.Known(throwable, 1013)
        is UpstreamResponseParseError -> ErrorLookupResult.Known(throwable, 1014)
        is VerificationResponseParseError -> ErrorLookupResult.Known(throwable, 1017)
        is VerificationNetworkError -> ErrorLookupResult.Known(throwable, 1018)

        // Impl-generic fallback: MLPA error we haven't enumerated.
        is MlpaError -> ErrorLookupResult.Known(throwable as Llm.Exception, 1099)

        // Llm.Exception we don't know about (some other impl or a bare Llm.Exception).
        is Llm.Exception -> ErrorLookupResult.Known(throwable, ErrorLookupResult.FALLBACK_CODE)

        // Truly unknown — a raw Throwable that escaped wrapping entirely.
        else -> ErrorLookupResult.Unknown(throwable)
    }
}
