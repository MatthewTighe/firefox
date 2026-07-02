/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.llm.adk

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Role as AdkRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mozilla.components.concept.llm.LlmModel
import mozilla.components.concept.llm.LlmSession
import mozilla.components.concept.llm.LlmTool

/**
 * An [LlmSession] backed by an ADK agent and runner. A single agent, wrapping [model] and any
 * [tools], drives a single in-memory conversation. The ADK runner manages history and, when
 * tools are configured, the tool-call loop; this class only surfaces the streamed text.
 */
internal class AdkLlmSession(
    model: LlmModel,
    systemPrompt: String,
    tools: List<LlmTool>,
) : LlmSession {

    private val agent = LlmAgent(
        name = AGENT_NAME,
        model = ConceptModelAdapter(model, tools),
        // Passed as a static instruction rather than a templated one so that literal '{...}'
        // sequences in the system prompt (e.g. recipe placeholders) are sent verbatim and not
        // interpreted as ADK session-state variables.
        staticInstruction = systemPrompt.takeIf { it.isNotEmpty() }
            ?.let { AdkContent(parts = listOf(AdkPart(text = it))) },
        tools = tools.map { LlmToolAdapter(it) },
    )

    private val sessionService = InMemorySessionService()
    private val runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)
    private val sessionKey = SessionKey(appName = APP_NAME, userId = USER_ID, id = SESSION_ID)

    private val sessionMutex = Mutex()
    private var sessionCreated = false

    override fun send(message: String): Flow<String> = flow {
        ensureSession()
        runner.runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = AdkContent(role = AdkRole.USER, parts = listOf(AdkPart(text = message))),
            runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        ).collect { event ->
            // Partial events carry text deltas; the terminating (non-partial) event repeats the
            // full text and is skipped here to avoid duplicating it downstream.
            if (!event.partial) return@collect
            event.content?.parts?.firstNotNullOfOrNull { it.text }?.let { emit(it) }
        }
    }

    private suspend fun ensureSession() = sessionMutex.withLock {
        if (!sessionCreated) {
            sessionService.createSession(sessionKey)
            sessionCreated = true
        }
    }

    private companion object {
        const val APP_NAME = "mozac-llm"
        const val USER_ID = "mozac-llm-user"
        const val SESSION_ID = "mozac-llm-session"
        const val AGENT_NAME = "mozac_llm_agent"
    }
}
