/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.summarize

import mozilla.components.concept.llm.LlmTool

/**
 * An [LlmTool] that gives the model the text content of the page the user is viewing. The model
 * calls this when it needs the page content to answer a question about it.
 *
 * @property getBody Supplies the page's text content when the tool is invoked.
 */
internal class PageContentTool(
    private val getBody: suspend () -> String,
) : LlmTool {
    override val name = "get_page_content"
    override val description =
        "Returns the text content of the web page the user is currently viewing. " +
            "Call this when you need the page's content to answer a question about it."
    override val parametersSchema: String? = null

    override suspend fun invoke(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("content" to getBody())
}
