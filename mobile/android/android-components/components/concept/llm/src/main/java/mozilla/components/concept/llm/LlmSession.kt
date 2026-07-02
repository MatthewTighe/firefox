/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.llm

import kotlinx.coroutines.flow.Flow

/**
 * A stateful conversation with a model. Hides the details of driving the model — history
 * management and, when tools are configured, the tool-call loop — from feature code.
 *
 * A session is bound to a single [LlmModel]. Switching models is done by creating a new
 * session; history does not carry across models.
 *
 * [companion object] is intentionally empty: the concrete factory lives in the module that
 * owns the agent machinery, exposed as an extension on this companion, keeping `concept-llm`
 * free of that dependency.
 */
interface LlmSession {
    companion object

    /**
     * Sends [message] as a user turn and streams the model's textual response.
     *
     * If tools are configured, any tool calls the model makes are executed and fed back
     * automatically; only the final natural-language text is emitted here.
     *
     * @param message The user's message.
     * @return A [Flow] of text deltas making up the response.
     */
    fun send(message: String): Flow<String>
}
