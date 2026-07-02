/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.adk

import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmSession
import mozilla.components.concept.llm.LlmTool

/**
 * Creates an [LlmSession] driven by the ADK runtime.
 *
 * @param model The backend the session runs inference against. Switching models means creating
 *  a new session; history does not carry across models.
 * @param systemPrompt An optional system instruction shaping the model's behavior.
 * @param tools Tools the model may call during the conversation. The session executes them and
 *  feeds their results back automatically.
 */
fun LlmSession.Companion.create(
    model: LlmModel,
    systemPrompt: String = "",
    tools: List<LlmTool> = emptyList(),
): LlmSession = AdkLlmSession(model, systemPrompt, tools)
