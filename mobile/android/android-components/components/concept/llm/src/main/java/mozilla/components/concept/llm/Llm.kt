/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.llm

/**
 * Marker interface for any failure surfaced by a cloud-based LLM provider.
 *
 * Implementation modules attach more specific category interfaces (e.g. [RateLimited],
 * [RequestTooLarge]) to their concrete exception types. Consumers may type-check the
 * categories to drive UI or recovery behavior without depending on any particular impl.
 */
interface CloudFailure

/** The request body or content exceeded what the service accepts. */
interface RequestTooLarge : CloudFailure

/**
 * Rate or token limit hit.
 *
 * @property retryAfter Seconds the caller should wait before retrying, if the service
 *  provided a hint. `null` if no hint was given.
 */
interface RateLimited : CloudFailure {
    val retryAfter: Long?
}

/** Authentication or authorization failure. */
interface AuthFailure : CloudFailure

/** A network-level failure reaching the service. */
interface NetworkError : CloudFailure

/**
 * The service responded with a server-side error.
 *
 * @property statusCode The HTTP status code returned.
 */
interface ServerError : CloudFailure {
    val statusCode: Int
}

/**
 * Marker interface for any failure surfaced by an on-device (local) LLM provider. Implementation
 * modules attach more specific categories to their concrete exception types so consumers can drive
 * UI without depending on a particular impl.
 */
interface LocalFailure

/** The on-device model is not available on this device (e.g. unsupported hardware). */
interface ModelUnavailable : LocalFailure

/** The on-device model failed to download. */
interface ModelDownloadFailed : LocalFailure

/** The on-device model failed while producing a response. */
interface ModelInferenceFailed : LocalFailure

/**
 * Namespace for LLM-level types shared across the concept. Retained as the home of
 * [Llm.Exception] so implementation modules and consumers have a stable, backend-agnostic
 * error type. Inference itself is expressed through [LlmModel] and [LlmSession].
 */
interface Llm {
    /**
     * An exception thrown by an LLM. Implementation modules may subclass this to
     * attach additional context (rate-limit metadata, HTTP status, etc.). Consumers
     * that need numeric codes for UI or telemetry should maintain their own mapping
     * from subtypes to codes.
     *
     * @param message A human-readable description of the failure.
     * @param cause The original throwable that caused this exception, if any.
     */
    open class Exception(
        message: String,
        cause: Throwable? = null,
    ) : kotlin.Exception(message, cause) {
        companion object {
            /**
             * Create an unspecified error.
             */
            fun unknown(message: String?) = Llm.Exception(
                message = message ?: "Unknown Llm Exception",
            )
        }
    }
}
