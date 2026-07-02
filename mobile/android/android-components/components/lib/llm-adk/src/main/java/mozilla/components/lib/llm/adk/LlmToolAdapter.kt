/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.adk

import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import mozilla.components.concept.llm.LlmTool

/**
 * Exposes a backend-agnostic [LlmTool] to the ADK runtime as an ADK [FunctionTool], so the
 * runner can offer it to the model and execute it on the model's behalf.
 */
internal class LlmToolAdapter(private val tool: LlmTool) : FunctionTool(
    name = tool.name,
    description = tool.description,
) {
    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = tool.name,
        description = tool.description,
        // Parameters are declared as untyped for now. Parsing [LlmTool.parametersSchema] into an
        // ADK Schema lands with tool calling.
        parameters = null,
    )

    override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any =
        tool.invoke(args)
}
