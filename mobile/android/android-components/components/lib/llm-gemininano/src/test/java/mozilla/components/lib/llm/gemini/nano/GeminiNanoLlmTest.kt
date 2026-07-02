/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.gemini.nano

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.llm.Content
import mozilla.components.concept.llm.Llm
import mozilla.components.concept.llm.LlmRequest
import mozilla.components.concept.llm.Role
import mozilla.components.lib.llm.gemini.nano.fakes.FakeGenerativeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

class GeminiNanoLlmTest {
    private fun request(userText: String) = LlmRequest(contents = listOf(Content.text(Role.User, userText)))

    @Test
    fun `generateContent streams the response when model is AVAILABLE and processes without error`() = runTest {
        val fakeModel = FakeGenerativeModel(
            status = sequenceOf(FeatureStatus.AVAILABLE),
            responseMap = mapOf("test prompt" to listOf("test response")),
        )

        val llm = GeminiNanoLlm(buildModel = { fakeModel })

        val results = llm.generateContent(request("test prompt")).toList()

        assertEquals("test response", results.last().content?.parts?.firstNotNullOfOrNull { it.text })
        assertEquals("test prompt", fakeModel.lastPromptProcessed)
    }

    @Test
    fun `generateContent fails when GenAiException is thrown`() = runTest {
        val fakeModel = FakeGenerativeModel(
            status = sequenceOf(FeatureStatus.AVAILABLE),
            exception = GenAiException(null, GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR),
        )

        val llm = GeminiNanoLlm(buildModel = { fakeModel })
        val result = runCatching { llm.generateContent(request("test prompt")).toList() }

        assertTrue(result.isFailure)
        assertIs<Llm.Exception>(result.exceptionOrNull())
        assertEquals("Gemini Nano inference failed: [ErrorCode 4] Request doesn't pass certain policy check. Please try a different input.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `logger delivers useful messaging during generateContent flow`() = runTest {
        val logMessages = mutableListOf<String>()
        val prompt = "test"
        val fakeModel = FakeGenerativeModel(
            status = sequenceOf(FeatureStatus.AVAILABLE),
            responseMap = mapOf(prompt to listOf("response")),
        )

        val llm = GeminiNanoLlm(
            buildModel = { fakeModel },
            logger = { logMessages.add(it) },
        )

        llm.generateContent(request(prompt)).toList()

        assertEquals(2, logMessages.size)
        assertTrue(logMessages[0].contains("Beginning model response stream"))
        assertTrue(logMessages[1].contains("Model stream completed with:"))
    }
}
