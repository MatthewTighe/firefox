# Ask-in-Page on ADK: Implementation Plan

Status: **research prototype**. Not tracked in Bugzilla. One continuous prototype
branch; each stage layers on the previous and is gated by a setting.

This document supersedes `llm-follow-up-implementation-plan.md`, which described a
hand-rolled LLM harness (custom `LlmSession`, tool-call loop, context-window
trimming). That design is discarded in favor of Google's Agent Development Kit
(ADK) for Kotlin/Android, which provides the agent, runner, session, tool, and
model abstractions we would otherwise have built ourselves.

This is a living document — update it with findings as we implement.

## Goals

1. Convert the existing shake-to-summarize (S2S) flow to run on the ADK.
2. Reach the "ask in page" prototype's functionality (agentic tool use, follow-up
   questions) on the ADK instead of the hand-rolled harness.
3. Let the AI modal swap between a remote model (MLPA) and a local model
   (Gemini Nano).

## Background: two divergent prototypes being converged

- **Ask-in-page stack** (`aip-llm-context`): a hand-rolled harness — streaming
  `ContextWindow`/`TurnResult`, an `LlmSession` with a tool-call loop, `LlmTool`,
  provider failover, context trimming, plus follow-up-question UI. Does **not**
  use ADK. Treated here as a reference/spec only, not landed.
- **ADK stack** (`adk` prototype): the thinnest possible ADK integration —
  implements ADK's `Model` interface (`MlpaModel`) routing MLPA's
  chat-completions through it, wrapped so `concept-llm` is unchanged, gated behind
  a `useAdkMlpaModel` secret setting. Pins ADK `0.1.0`. Single-turn, no tools.

The through-line: make ADK the engine, keep `concept-llm` as a thin ADK-shaped
seam over it, rebuild the ask-in-page capabilities on ADK primitives, then make
the model pluggable (remote MLPA <-> local Nano).

## ADK facts (v0.4.0)

Current published version is **v0.4.0** (`com.google.adk:google-adk-kotlin-core-android`,
`com.google.adk:google-adk-kotlin-processor`). The prototype pins `0.1.0`; the
`Model` contract is unchanged between them.

Key contracts (package `com.google.adk.kt.*`):

- **Model backend:** `interface Model { val name: String; fun generateContent(request: LlmRequest, stream: Boolean = false): Flow<LlmResponse> }`.
  MLPA is a custom `Model`.
- **Gemini Nano:** `com.google.adk.kt.models.mlkit.GenaiPrompt.create(generativeModel, name)`
  wraps `com.google.mlkit.genai.prompt.GenerativeModel` — exactly the type our
  `lib-llm-gemininano` already builds. Nano-as-a-model is nearly free.
- **Agentic loop:** `InMemoryRunner(agent, sessionService).runAsync(userId, sessionId, newMessage, runConfig): Flow<Event>`
  drives the tool-call loop internally. We do not hand-roll it.
- **Agent:** `LlmAgent(name, description, model, instruction, tools)`.
- **Tools:** subclass `FunctionTool(name, description)` implementing
  `declaration(): FunctionDeclaration?` and `execute(context, args): Any`. Tools
  travel in `LlmRequest.config.tools` as `FunctionDeclaration`s; the model returns
  `Part(functionCall = FunctionCall(name, args, id))`. `FunctionTool` includes a
  human-in-the-loop `requiresConfirmation` hook (unused for now).
- **Sessions:** `InMemorySessionService`, or `RoomSessionService` (Android,
  on-disk) if durable chats are ever wanted.
- **Types:** `Content` / `Part` / `Role`, `FunctionCall` / `FunctionDeclaration` /
  `FunctionResponse`, `GenerateContentConfig`, `Schema`, `FinishReason`, etc.
  `StreamingResponseAggregator` assembles streamed text + function-call deltas.

MLPA already supports OpenAI-style server-side tool calling (`tool_calls`,
`toolChoice`), proven in the ask-in-page stack — so ADK's tool loop maps onto a
real MLPA capability.

## Architecture

`concept-llm` is a **thin, ADK-shaped seam**: it re-declares only the subset of
ADK types we use (named/shaped like ADK, so we track the emerging standard), maps
to real `com.google.adk.*` internally, and is the only thing feature modules see.
This keeps the door open to replacing the internals later without touching
callers.

### Where adapting code lives

- **Shared** concept <-> ADK adapting code (used by more than one backend, e.g.
  by both Nano and MLPA) lives in its **own `lib` module**.
- **Backend-specific** adapting code lives in the library that defines that
  backend (`lib-llm-mlpa`, `lib-llm-gemininano`).

Feature modules never import `com.google.adk.*`.

## Stage 0: Build & dependency plumbing (prep)

- Bump `google-adk-kotlin-*` from `0.1.0` -> `0.4.0` (core-android + KSP
  processor) in the modules that own the ADK dependency.
- Carry over the prototype's dexing survival kit: `protobuf-java` exclusion (keep
  javalite), the `META-INF/*` packaging excludes in `fenix/app/build.gradle`, KSP
  plugin.
- **Tracked risk (harden-later blocker):** ADK pulls in `google-genai`,
  `google-auth-*`, `api-common`, Apache HttpClient, Netty. Measure the
  APK-size/method-count delta and licensing surface once it builds; this is the
  go/no-go gate before the prototype can ship. Flag now, solve later.

## Stage 1: Foundational ADK conversion (goal 1)

Reshape `concept-llm` and route S2S through the ADK Runner with **zero tools**
(pay the plumbing once).

1. **`concept-llm` reshape (thin seam):**
   - `Content` / `Part` / `Role` — text + `functionCall` + `functionResponse`
     parts only.
   - `LlmModel` — mirrors ADK `Model`. MLPA and Nano implement our interface.
   - `LlmSession` — mirrors `Runner` + `Session`: `send(message): Flow<...>`,
     backed by `InMemoryRunner` internally.
   - `LlmTool` — mirrors `FunctionTool` (name, description, params schema,
     `execute`). Declared now, unused until Stage 2.
   - **Keep** the existing provider-lifecycle/download-state types
     (`CloudLlmProvider` / `LocalLlmProvider` `State`, download progress) — ADK
     does not model Nano's download phase, and Stage 3 needs them.
   - Remove the old single-shot `Prompt` + `Llm.prompt(Prompt): Flow<String>`
     once callers migrate.
2. **`MlpaModel`** (from the ADK prototype, upgraded to 0.4.0): implements
   `LlmModel`, single-turn text only for now.
3. **Adapters:** shared concept <-> ADK converters in their own `lib` module;
   MLPA-specific conversions in `lib-llm-mlpa`.
4. **`feature-summarize`:** replace the direct prompt call with
   `LlmSession.send(pageContent)`; render streamed text as today. No follow-up UI
   yet.
5. **Drop the `useAdkMlpaModel` dual-path toggle** — ADK becomes the only path.
6. **Tests:** `MlpaModel` conversion tests; migrate `feature-summarize`
   store/middleware tests to the `LlmSession` seam; update Nano/MLPA fakes.

End state: S2S behaves exactly as before, but every token flows through ADK's
Runner/Agent/Model. Nothing user-visible changed.

## Stage 2: Ask-in-page (goal 2)

Add the agentic capability on top of Stage 1's Runner. Architecture: the agent is
given tools to reach into the page (not a giant single-shot context).

1. **`MlpaModel` tool translation (the real work):** read
   `LlmRequest.config.tools` (`FunctionDeclaration`s) -> emit MLPA OpenAI-style
   `tools` + tool-choice; parse MLPA streaming `tool_calls` ->
   `Part(functionCall = FunctionCall(...))`. Port the `tool_calls` parsing from
   the ask-in-page stack's `MlpaService`; ADK's `StreamingResponseAggregator`
   handles assembly on its side.
2. **Page-content tool:** an `LlmTool` (-> ADK `FunctionTool`) whose `execute()`
   calls `feature-summarize`'s existing `ContentProvider`/extraction. Built at the
   seam; the feature registers it via `LlmSession` config. Initially the only
   tool; architected so more can be added later.
3. **Agent instruction:** system prompt telling the model it can pull page content
   via the tool across rounds. The Runner drives the multi-round loop.
4. **`feature-summarize` UI/state:** add the follow-up-question flow, reusing the
   shape of the old stack's Redux states/actions (`AwaitingFollowUp` ->
   `RespondingToFollowUp` -> `FollowUpComplete`) as reference, reimplemented
   against `LlmSession`. Multiple questions per session (ADK `Session` retains
   history).
5. **Tests:** tool round-trip through `MlpaModel`; a fake `Model` emitting a
   function call to exercise the Runner loop end-to-end; follow-up state-machine
   transitions.

## Stage 3: Local/remote model swap (goal 3)

1. **Nano as an ADK model:** wrap `lib-llm-gemininano`'s `GenerativeModel` in
   `GenaiPrompt.create(...)` behind an `LlmModel`. Minimal — ADK ships the
   adapter. Backend-specific adapting lives in `lib-llm-gemininano`.
2. **Swap = rebuild:** selecting a model rebuilds the `LlmAgent` + `Runner` -> a
   fresh `LlmSession`, **history reset**. No cross-capability translation. When
   Nano is active, the page-content tool is simply not registered (Nano has no
   tool calling) — no degradation logic needed.
3. **Setting + debug-drawer toggle** for model selection (taking over the old
   secret-setting toggle's role).
4. **Tests:** model-selection setting drives which `LlmModel` the session builds;
   swapping clears history; Nano session has no tools registered.

## Cross-cutting

- **Threading:** collect `runAsync` on `Dispatchers.IO`; marshal UI updates on
  `Main`.
- **Sessions:** `InMemorySessionService` to start; `RoomSessionService` is the
  drop-in for persistent chats.
- **Verification:** the affected Gradle lib modules must be built directly (not
  `./mach build faster`) with their unit tests per stage; `./mach test --auto` /
  `mach try` before anything lands.

## Implementation log

### Stage 0 + Stage 1 (landed together)

Done and green: `concept-llm` reshaped; new `lib-llm-adk` module; MLPA and Nano ported to
`concept.LlmModel`; `feature-summarize` routed through `LlmSession`; Fenix app packaging
excludes added. Unit tests pass for `concept-llm`, `lib-llm-adk`, `lib-llm-mlpa`,
`lib-llm-gemininano`, and `feature-summarize` (the summarize store test exercises the real
ADK runner end-to-end with a fake model). ADK **0.4.0** verified against the tree.

Build note: use **`./mach gradle`**, which sets up the mozconfig environment (correct
GeckoView, repositories, etc.). Fenix app project path is `:fenix` (e.g.
`./mach gradle :fenix:compileDebugKotlin`); AC module tests run as
`./mach gradle :components:<name>:testDebugUnitTest`. Invoking `./gradlew` directly with only
`ANDROID_HOME` set resolves a mismatched GeckoView and fails with
`onAudioSessionTypeChanged overrides nothing` in `browser-engine-gecko` — that is a
harness/invocation artifact, not a real bustage. The full `:fenix` app compiles cleanly via
`./mach gradle`.

### Findings worth remembering

- **ADK instruction templating gotcha.** `LlmAgent(instruction = Instruction(text))` runs
  `{...}` placeholder substitution from session state. Our recipe system prompt contains
  literal `{servings}`, `{total_time}`, etc., which made the runner throw
  `Context variable not found: 'servings'`. Fix: pass the system prompt as
  `staticInstruction = Content(parts = [Part(text = ...)])`, which ADK sends verbatim (no
  substitution) and routes into `LlmRequest.config.systemInstruction` — exactly what our
  model adapter reads.
- **Streaming contract.** A custom ADK `Model` emitting partial `LlmResponse` text deltas
  plus a final non-partial aggregate is surfaced by the runner as partial `Event`s carrying
  the deltas; `AdkLlmSession.send` emits only the partial-event text, which the existing
  `mapToRichDocument` accumulates. Confirmed by the summarize store test.
- **Module layering realized.** Backends (`lib-llm-mlpa`, `lib-llm-gemininano`) implement
  `concept.LlmModel` and stay ADK-free; `lib-llm-adk` is the only module importing
  `com.google.adk.*`. `MlpaLlm` was renamed to `MlpaModel`.

### Stage 2 (agentic tool use + follow-up questions)

Done and green: the ADK tool loop runs end-to-end through the seam; MLPA speaks OpenAI-style
tool calling; the summarize session registers a page-content tool and stays alive for
follow-up questions; a follow-up question flow (state machine + Compose UI) was added. Unit
tests pass for `lib-llm-adk`, `lib-llm-mlpa`, `lib-llm-gemininano`, and `feature-summarize`.

What landed:

- **Tool loop (lib-llm-adk).** `ConceptModelAdapter` forwards registered tools (and a
  `ToolChoice`) into the concept `LlmRequest`; `Part.functionCall`/`functionResponse`
  round-trip both directions. A test with a fake model that requests a tool + a fake tool
  proves the ADK runner executes the tool and streams the final text — validating the whole
  agentic loop without needing a live backend.
- **MLPA tool calling (lib-llm-mlpa).** `ChatService.completion` now returns
  `Flow<TurnEvent>` (`TextDelta` | `ToolCalls`); the SSE parser accumulates streamed
  `tool_calls` by index. `MlpaModel` forwards `LlmRequest.tools` as OpenAI-style `tools` +
  `tool_choice`, maps concept function-call/response history to assistant/tool messages, and
  surfaces `tool_calls` as `Part.functionCall`. `explicitNulls = false` keeps null
  tool/content fields out of the wire format.
- **Page-content tool (feature-summarize).** `PageContentTool` (an `LlmTool`) returns the
  extracted page body; it is registered on the summarize `LlmSession`. The initial summary
  still pre-injects the body (unchanged behavior); the tool is available to the agent for
  follow-ups.
- **Follow-up flow.** New `RespondingToFollowUp`/`FollowUpComplete` states + actions; the
  middleware keeps the `LlmSession` alive and runs follow-ups on it (cleared on
  `ViewDismissed`). `FollowUpContent` renders the summary, follow-up Q&A, and an input.

Findings worth remembering:

- **A custom ADK `Model` needs the function-call event to be non-partial** for the runner to
  act on it; text is streamed as partial deltas plus a final aggregate.
- **`toolsDict` vs `config.tools`.** The runner matches a returned function call to a tool
  registered on the agent (by name), not to `config.tools`. Since our custom model advertises
  tools to MLPA itself, `LlmToolAdapter.declaration()` can leave `parameters = null` and the
  loop still works; the real JSON schema goes to MLPA via `MlpaModel`.

### Stage 3 (local/remote model swap)

Done and green: Nano is selectable as the summarization backend alongside MLPA, controlled by
a setting + debug-drawer toggle; switching resets the conversation. `feature-summarize` tests
pass and the full `:fenix` app compiles via `./mach gradle`.

What landed:

- **Nano SDK.** Already on the latest published `com.google.mlkit:genai-prompt` (`1.0.0-beta2`,
  released April 2026 — no newer version exists), so no bump was needed. Nano needs no ADK
  wrapper: `GeminiNanoLlm` already implements `concept.LlmModel`, so it drops straight into an
  `LlmSession` via `lib-llm-adk`.
- **Provider-agnostic middleware.** `SummarizationMiddleware` now takes any `LlmProvider`
  (cloud or local). A unified `LlmProvider.fetchLlm` maps both lifecycles to actions; for a
  local provider it triggers `downloadIfNeeded` (new `ProviderNeedsDownload` action) and maps
  the download progress to the existing `Downloading` state (new `ModelDownloadProgress`
  action + reducer branch).
- **Fenix wiring.** Added `GeminiNanoLlmProvider` to the `Llm` component and a
  `useLocalSummarizationModel` secret setting (Settings.kt + preference key + static string +
  `secret_settings_preferences.xml` + `SecretSettingsFragment`). `SummarizationFragment`
  selects the Nano or MLPA provider from the setting; the ViewModel factory now takes
  `LlmProvider`.
- **History reset on swap.** Each summarize invocation builds a fresh `LlmSession` from the
  currently-selected provider, so flipping the toggle naturally starts a new conversation — no
  cross-model history translation needed.

Where the toggle lives: the "Summarize on device" toggle is part of the shared android-components
`SummarizeSettingsStore` (`SummarizationSettings.getUseLocalModel`/`setUseLocalModel`,
`SummarizeSettingsState.isLocalModelEnabled`, `LocalModelPreferenceToggled`), rendered by
`SummarizeSettingsContent`. Because both Fenix surfaces render that shared component, it appears
in **Settings > Page summaries** and in the **in-modal settings gear** automatically, from one
implementation. The summarize fragment reads the choice synchronously from
`SummarizationSettingsCache.useLocalModel` when picking the provider. (An earlier iteration used
a Fenix `Settings` SharedPreferences flag + a debug-drawer secret setting; that was replaced by
the A-C store so both surfaces stay correct from a single source.)

Follow-up gear fix: the settings gear now also works from the `FollowUpComplete` state (screen
wires `onSettingsClicked` there and the reducer handles `SettingsClicked` from it); previously
the gear only worked in the base `Summarized` state, so it went dead after asking a follow-up.

Note: when Nano is selected the page-content tool is still registered, but Nano ignores tools
(it flattens the request to a single prompt), so follow-ups rely on conversation history
rather than tool calls — acceptable for the prototype.

### Suggestion-driven entry (replaces auto-summary)

The modal no longer summarizes automatically. Once the provider is ready and page content is
fetched, it lands in `AwaitingRequest` showing three static suggestion cards ("Summarize this
page", "What are the key points?", "Explain this in simple terms") above the prompt. Selecting a
card (or typing) creates a session with the matching instruction and sends the page body.

- Instructions live in `ext/Content.kt` (`keyPointsInstructions`, `simpleTermsInstructions`,
  `generalQaInstructions`) alongside the existing `defaultInstructions`/`recipeInstructions`;
  `SummarizationSuggestion` maps a card to its instruction (Summarize reuses the recipe-aware
  `systemPrompt`).
- State machine simplified: `AwaitingRequest` + `Thinking` added; the separate
  `RespondingToFollowUp`/`FollowUpComplete` states were removed — every request (suggestion or
  typed) flows `Thinking` -> `Summarizing` -> `Summarized`, and follow-ups reuse the session.
- While waiting for the first token, a `Thinking…` bubble is shown (in `ThinkingContent`)
  instead of the old loading screen; the text is animated with the brand gradient
  (`summaryGradientColors`) sweeping across it.
- `SummarizationStoreTest` and the reducer test were rewritten for the no-auto-summary flow.

## Open items (resolve during Stage 0/1; not blockers now)

- Exact MLPA tool-choice <-> ADK `FunctionCallingConfig` mapping (verify against
  the artifact).
- Precise home for the shared adapter `lib` module (name, package).
- The dependency-size / licensing writeup for the harden-later gate.
