/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.gemini.nano

import mozilla.components.concept.llm.Llm
import mozilla.components.concept.llm.ModelDownloadFailed
import mozilla.components.concept.llm.ModelInferenceFailed
import mozilla.components.concept.llm.ModelUnavailable

/**
 * The Gemini Nano feature is not available on this device (e.g. unsupported hardware or the
 * on-device AI service is missing).
 */
class GeminiNanoUnavailable :
    Llm.Exception("Gemini Nano is unavailable on this device"), ModelUnavailable

/**
 * The Gemini Nano model failed to download.
 */
class GeminiNanoDownloadFailed :
    Llm.Exception("Gemini Nano model download failed"), ModelDownloadFailed

/**
 * Gemini Nano failed while producing a response.
 *
 * @param cause The underlying on-device inference failure.
 */
class GeminiNanoInferenceError(cause: Throwable) :
    Llm.Exception("Gemini Nano inference failed: ${cause.message}", cause), ModelInferenceFailed
