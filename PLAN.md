# NexusAgent — Architecture & Build Plan

> An autonomous OS-level UI agent for Android. Speak a goal; the phone drives itself.

**Status:** Planning complete, implementation not started
**Target device:** Physical Android 12+ (API 31+)
**Primary reasoning model:** Google Gemini (default), pluggable to Anthropic / OpenAI via user-supplied key
**Deliverable:** Sideloaded APK + GitHub repo + 60-second demo video

---

## 1. What this is

NexusAgent turns a natural-language instruction into a sequence of real touch events on a real phone.

```
"Set an alarm for 6am"  →  agent opens Clock, taps +, types 6:00, taps Save  →  done
```

It does this **without any per-app integration**. There is no WhatsApp SDK, no Instagram API, no MakeMyTrip partnership. The agent reads the screen the way a screen reader does, decides what a human would tap next, and taps it. Any app that renders UI on Android is automatically supported.

This is the mobile equivalent of Anthropic's computer-use and Google's Project Mariner — the research literature calls these **GUI agents**.

### Why it's a strong placement project

It is simultaneously an **OS-internals** project (accessibility framework, gesture injection, Binder IPC, security sandbox), a **distributed-systems-in-miniature** project (async pipeline, retries, state machine, failure recovery), and an **applied-AI** project (ReAct loop, structured outputs, context compression). Very few student projects touch all three. It also demos in 30 seconds on a phone screen, which matters more than it should.

---

## 2. The core loop

Everything in this project exists to serve one loop:

```
┌────────────────────────────────────────────────────────────┐
│                                                            │
│   PERCEIVE ──► COMPRESS ──► REASON ──► ACT ──► VERIFY ──┐  │
│      ▲                                                  │  │
│      └──────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

| Stage | What happens | Where |
|---|---|---|
| **Perceive** | Walk the live `AccessibilityNodeInfo` tree of the foreground app | `:agent:perception` |
| **Compress** | Prune ~90% of nodes, emit token-efficient JSON + optional screenshot | `:agent:perception` |
| **Reason** | Send goal + history + screen to the LLM, get back one `{thought, action}` | `:agent:reasoning` |
| **Act** | Perform a node action, or dispatch a raw gesture, or fire an Intent | `:agent:execution` |
| **Verify** | Wait for the screen to settle, re-snapshot, confirm something changed | `:agent:orchestrator` |

One iteration = one **step**. A task is typically 3–12 steps. Hard cap: 25.

---

## 3. Module structure

Multi-module Gradle, both for build speed and because it reads well in a code review.

**Sized for a 7.4 GB / 6-core dev machine — 6 modules, not 12.** Each Gradle module costs configuration time and daemon memory; on this hardware a finer split would cost more in build overhead than it returns. Six still demonstrates multi-module architecture clearly. On a 16 GB machine, splitting `feature/*` and `agent:runtime` further would be reasonable.

```
NexusAgent/
├── app/                      Application, DI graph, navigation, and ALL feature UI:
│                             onboarding wizard · home/mic orb · overlay bubble · history
├── core/
│   ├── model/                Sealed action classes, ScreenSnapshot, AgentState
│   │                         Pure Kotlin, zero Android deps → JVM-unit-testable
│   ├── ui/                   Material 3 Expressive theme, motion specs, shared composables
│   └── data/                 Room (run history), DataStore (encrypted keys), repositories
└── agent/
    ├── perception/           AccessibilityService, tree walker, compressor, screenshot capture
    └── runtime/              LlmProvider impls + prompt builder · gesture dispatcher +
                              node resolver + Intent registry · orchestrator state machine
```

**Dependency rule:** `app` → `agent/*` → `core/*`. Nothing points back up. `core:model` has no Android dependency at all, so the action schema and state machine are unit-testable on the JVM with no emulator — which matters a lot on a machine that can't comfortably run one.

**Inside `agent:runtime`**, keep `reasoning/`, `execution/`, and `orchestrator/` as separate packages with the same boundaries they'd have as modules. If you later move to a stronger machine, promoting them to real modules is then a mechanical change.

---

## 4. Module specs

### 4.1 Perception — reading the screen

**The service**

```kotlin
class NexusAccessibilityService : AccessibilityService()
```

Configured via `res/xml/accessibility_config.xml`:
- `canRetrieveWindowContent="true"`
- `canPerformGestures="true"`
- `canTakeScreenshot="true"`
- `accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"`
- `accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"`

**Critical design decision:** the service does **not** parse the tree on every accessibility event. A busy app fires hundreds of `typeWindowContentChanged` events per second and parsing each one would melt the device. Instead:

- Events feed a **settle detector** — a `MutableSharedFlow` debounced at 400ms. When events go quiet, the screen is "settled."
- The tree is walked **only on demand**, when the orchestrator asks for a snapshot.

This is the single most important performance decision in the project, and a great thing to explain in an interview.

**The compressor**

Recursive DFS over `rootInActiveWindow`. A node is **kept** if any of:

```
isClickable · isLongClickable · isCheckable · isEditable · isScrollable · isFocusable
  · has non-blank text · has non-blank contentDescription
```

and it is `isVisibleToUser` with non-empty bounds.

Pruned container nodes don't just vanish — their text is **hoisted** into the nearest kept ancestor, so a `LinearLayout > TextView("₹4,299")` collapses into one element that retains the price. Without hoisting you lose the very data the model needs.

Each kept node gets a **stable per-snapshot integer id** the model refers to. The id maps to a `ResolvedTarget` — see §4.4 for why we don't hand raw node objects to the executor.

**Output schema** (short keys deliberately — every character is a token):

```json
{
  "pkg": "com.google.android.deskclock",
  "act": "DeskClock",
  "el": [
    {"i":0,"c":"Button","d":"Add alarm","k":1,"b":[880,1680,1040,1840]},
    {"i":1,"c":"TextView","t":"6:00 AM","k":1,"b":[48,420,400,520]},
    {"i":2,"c":"EditText","d":"Alarm name","e":1,"b":[48,560,1032,640]},
    {"i":3,"c":"RecyclerView","s":1,"b":[0,300,1080,1600]}
  ]
}
```

`i`=id · `c`=class · `t`=text · `d`=contentDescription · `k`=clickable · `e`=editable · `s`=scrollable · `b`=bounds

**Instrument this.** Log `rawNodeCount`, `keptNodeCount`, `rawXmlBytes`, `compressedJsonBytes` for every snapshot and persist to Room. That table becomes your resume metric, and it's a *measured* number rather than a claimed one.

**Screenshots**

`takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)` — API 30+, no MediaProjection, no per-session consent dialog, no foreground service required. Downscale to 768px wide, JPEG quality 70, base64.

Attached to the LLM request **only when** one of:
- kept element count < 4 (screen is likely canvas-rendered — e.g. Instagram feed, games)
- the previous action produced no screen change
- the model explicitly emitted `request_screenshot`

This keeps typical steps text-only and cheap, while staying robust on apps that expose nothing to accessibility. That conditional policy is the "hybrid" in hybrid perception, and it's the part worth talking about.

---

### 4.2 Reasoning — the LLM layer

**Provider abstraction**

```kotlin
interface LlmProvider {
    val id: ProviderId
    val supportsVision: Boolean
    suspend fun decide(request: AgentTurn): Result<AgentDecision>
}
```

Three implementations: `GeminiProvider` (default), `AnthropicProvider`, `OpenAiProvider`. Each enforces the same JSON schema through its own native mechanism — Gemini's `responseSchema`, Anthropic's forced `tool_use`, OpenAI's `response_format: json_schema`.

**Provider selection has zero subscription detection.** Settings shows a provider picker and an API-key field. Whatever key the user pastes determines the route. Gemini is preselected because it has a usable free tier. This is the only correct design — no client app can inspect another vendor's billing status, and consumer chat subscriptions never include API credits.

Keys are stored in DataStore encrypted with a key from the Android Keystore. Never in `local.properties` committed to git, never in `strings.xml`.

**Prompt structure** (ReAct)

```
SYSTEM
  You are an Android UI automation agent. You see a compressed screen
  representation and choose exactly ONE next action.
  <rules>
  <action schema>
  <intent shortcut catalogue>

USER
  GOAL: "message Rahul on WhatsApp that I'll be 20 minutes late"
  STEP: 4 of 25
  HISTORY:
    1. thought="Need WhatsApp open"    action=launch_app(com.whatsapp)   → ok
    2. thought="Find Rahul in list"    action=click(7)                   → ok
    3. thought="Focus message box"     action=click(12)                  → ok, keyboard shown
  CURRENT SCREEN: { ...compressed json... }
  [optional image]
```

**Response schema — strictly enforced:**

```json
{
  "thought": "The message field is focused, so I type the message now.",
  "action": "type",
  "args": { "id": 12, "text": "I'll be 20 minutes late", "submit": false },
  "confidence": 0.9
}
```

The `thought` field is not decoration. It is what streams to the floating overlay so the user watches the agent reason in real time — the single best thing in the demo video.

---

### 4.3 Action schema

Modelled as a Kotlin `sealed interface` in `core:model`, serialized with a `kotlinx.serialization` polymorphic discriminator.

| Action | Args | Notes |
|---|---|---|
| `click` | `id` | Node action, gesture fallback |
| `long_click` | `id` | |
| `type` | `id, text, submit` | `submit=true` also fires IME action |
| `scroll` | `direction, id?` | Node scroll, gesture fallback |
| `swipe` | `x1,y1,x2,y2,durationMs` | Raw coordinates, for canvas apps |
| `global` | `BACK / HOME / RECENTS / NOTIFICATIONS` | `performGlobalAction` |
| `launch_app` | `packageName` | Skips 3 steps of home-screen hunting |
| `intent` | `name, params` | See below |
| `wait` | `ms` | For loading screens |
| `request_screenshot` | — | Model asks to see pixels |
| `ask_user` | `question` | Missing info — pauses and prompts |
| `done` | `summary` | Terminal success |
| `fail` | `reason` | Terminal failure |

**Intent shortcuts** — the hybrid-action idea. Some tasks have a first-class Android Intent that is instant and cannot fail:

| Shortcut | Backing Intent |
|---|---|
| `set_alarm` | `AlarmClock.ACTION_SET_ALARM` |
| `set_timer` | `AlarmClock.ACTION_SET_TIMER` |
| `dial` | `Intent.ACTION_DIAL` |
| `open_url` | `Intent.ACTION_VIEW` |
| `share_text` | `Intent.ACTION_SEND` |
| `create_event` | `CalendarContract` insert |

The catalogue is injected into the prompt so the model *chooses* between an Intent and UI navigation. "Set an alarm for 6am" becomes one deterministic step instead of six fragile ones — while "like the third post on Instagram" still goes the UI route because no Intent exists.

This is a genuinely good architectural point for interviews: **the agent has a fast path and a general path, and picks.**

---

### 4.4 Execution — making it actually tap

**Node staleness is the hardest real bug in this project.** `AccessibilityNodeInfo` objects are Binder handles into another process. Between the snapshot and the tap, the target may have scrolled, recycled, or been destroyed. Holding references and calling `performAction` on them later fails intermittently and confusingly.

**Solution — re-resolve at execution time.** The snapshot stores a `ResolvedTarget` descriptor, not a node:

```kotlin
data class ResolvedTarget(
    val viewIdResourceName: String?,   // strongest signal
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val bounds: Rect,                  // last-resort fallback
)
```

At execution, resolve in priority order: `viewId` → `text` → `contentDescription` → `bounds` containment. Only if all fail do we tap raw coordinates via `dispatchGesture`.

**Per-action fallback chains:**

- **Click** — `ACTION_CLICK` on node → walk up to nearest clickable ancestor → `dispatchGesture` tap at bounds centre
- **Type** — `ACTION_SET_TEXT` → focus + clipboard paste → per-character `dispatchGesture` (never needed in practice, but the chain is the point)
- **Scroll** — `ACTION_SCROLL_FORWARD` on scrollable node → `dispatchGesture` swipe across the node's bounds

**Verification** happens after every action:
1. Await screen-settled (debounced flow, 2s timeout)
2. Re-snapshot
3. Compare `screenSignature` = hash of (package + activity + sorted element texts)
4. Signature unchanged → the action was a **no-op**; report `ineffective` back to the model so it tries something else

Automatic retry: 2 attempts with escalating fallback, then surface the failure into history rather than crashing the run.

---

### 4.5 Orchestrator — the state machine

MVI, single source of truth, `StateFlow<AgentState>`.

```
Idle
 └─► Listening ──► Transcribing ──► Planning ◄──────────────┐
                                      │                     │
                                      ├─► Executing ──► Verifying ──┘
                                      ├─► AwaitingConfirmation ──┘
                                      ├─► AwaitingUserInput ─────┘
                                      ├─► Done(summary)
                                      └─► Failed(reason)
```

**Guards — this is your "resilient systems" story:**

| Guard | Rule |
|---|---|
| Step budget | Hard stop at 25 steps |
| Wall clock | Hard stop at 3 minutes |
| Loop detection | Hash `(screenSignature, action)`; same pair 3× → inject "you are stuck, try a different approach" and force a re-plan |
| Oscillation | A→B→A→B screen ping-pong detected over a 4-step window |
| Crash recovery | Foreground package changes to launcher unexpectedly → re-orient instead of blindly continuing |
| Destructive gate | `send message` / `pay` / `delete` / `confirm order` keywords → pause in `AwaitingConfirmation`, user taps to approve on the overlay |
| Kill switch | Stop button on the overlay + long-press volume-down, both cancel the coroutine scope immediately |

**Threading:** the whole loop runs on `Dispatchers.Default` inside a scope owned by a foreground `Service`, so it survives the UI being backgrounded — which it always is, since the agent is by definition driving *other* apps. Networking and JSON go to `Dispatchers.IO`. The accessibility callback thread does nothing but push into a flow.

---

### 4.6 Voice input

`SpeechRecognizer` with `EXTRA_PREFER_OFFLINE = true` (Android 12+ ships offline models, so this works on a plane and adds no latency or cost).

- Tap-and-hold to record, release to submit — plus a tap-to-toggle mode
- `onRmsChanged` amplitude drives the Compose waveform in real time
- `onPartialResults` streams live text under the orb
- Always-available text input fallback

---

### 4.7 UI — where the "very interactive" requirement lives

Jetpack Compose, Material 3 **Expressive** (the 2025 motion-physics system: springy shape morphing, not linear tweens).

**Onboarding** — a permission wizard, because this app needs unusual permissions and a raw system dialog with no explanation looks like malware:
- Accessibility service (deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS` with an animated illustration of which toggle to flip)
- Overlay permission (`SYSTEM_ALERT_WINDOW`)
- Microphone, notifications
- API key entry

**Home** — a single large mic orb built with Compose `Canvas`: layered sine-wave blobs on an `rememberInfiniteTransition`, morphing between idle-breathing, listening-reactive (amplitude-driven), and thinking-swirl states. Below it, suggestion chips of example commands. Behind it, a subtle animated mesh gradient.

**Overlay (the demo centrepiece)** — a draggable bubble that persists over WhatsApp, Instagram, anything:
- Collapsed: pulsing dot + step counter
- Expanded: current `thought` streaming in, action chip, stop button
- **A highlight rectangle animates onto the target element ~200ms before the tap fires.** This is what makes a viewer go "oh, it's actually seeing the screen." Do not skip this.

**History** — Room-backed run list. Tap a run to replay it step by step: the thought, the action, the compressed screen, the screenshot, the latency, the token count. This doubles as your debugging tool *and* your interview artifact.

**Settings** — provider + key, step budget, confirm-destructive toggle, verbose-trace toggle.

---

### 4.8 Persistence

Room, three tables:

```
runs(id, goal, startedAt, endedAt, status, provider, totalTokens, totalSteps)
steps(id, runId, index, thought, actionJson, result, latencyMs,
      rawNodeCount, keptNodeCount, rawBytes, compressedBytes, screenshotPath)
settings → DataStore, not Room
```

The instrumentation columns on `steps` are what turn "I optimized the context window" into "here is the p50 compression ratio across 400 recorded steps."

---

## 5. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x | Coroutines, Flow, sealed hierarchies |
| UI | Jetpack Compose + Material 3 Expressive | The animation quality you want is impractical in XML |
| Async | Coroutines + Flow | Structured concurrency = a working kill switch for free |
| DI | Hilt | Standard, and cleanly injects into a `Service` |
| Network | Ktor Client (OkHttp engine) | Multiplatform-ready, and writing the API layer by hand shows more than a vendor SDK |
| Serialization | kotlinx.serialization | Polymorphic sealed-class support is exactly the action schema's shape |
| DB | Room + KSP | |
| Prefs | DataStore + Android Keystore | Encrypted API keys |
| OS | AccessibilityService, `dispatchGesture`, `takeScreenshot`, `SpeechRecognizer` | |
| Build | Gradle KTS + version catalog + convention plugins | Multi-module hygiene |
| Test | JUnit5, Turbine (flows), Robolectric, MockK | The compressor and state machine are pure-JVM testable |

**Deliberately not used:** MediaProjection (`takeScreenshot` is strictly better here), Retrofit (Ktor covers it), root/ADB shell (breaks the "any user can install this" story).

---

## 5.5 On-device inference (Offline Mode)

Running the reasoning model locally is viable and turns "on-device agent" from an overclaim into an accurate description. It is planned as a **switchable mode**, not the default, and is deliberately scheduled after the cloud loop works (see M8).

### Options

| Route | Notes |
|---|---|
| **MediaPipe / Google AI Edge LLM Inference API** | Recommended. Gemma 3 1B/4B, Phi-4-mini, Qwen 2.5 in int4/int8, GPU-accelerated, clean Kotlin API. Gemma 3 4B is multimodal, so the screenshot path survives. |
| llama.cpp via JNI/NDK | Any GGUF model, maximum control, most integration work |
| Gemini Nano (ML Kit GenAI / AICore) | Already resident on Pixel 9+/Galaxy S24+, but exposes task-shaped APIs (summarize/rewrite/proofread) rather than free-form structured tool-calling. **Not a fit for the action loop.** |
| Qualcomm QNN / Samsung ENN (NPU) | Fastest, vendor-locked, high effort |

### The three constraints

1. **Output quality is the real blocker — not speed.** The task is "read 40 elements, plan multi-step, pick one, emit strict JSON." Small models are weak at precisely that combination. Expect wrong-element taps and schema violations at a rate that makes *unattended* local-only runs unreliable.
2. **Prefill latency dominates.** Each step sends ~1–3K tokens of screen JSON. Decode speed is the number everyone quotes; prefill is the one you feel. Budget several seconds per step on a flagship, worse on midrange.
3. **RAM and thermals.** A 4B int4 model is ~2.5–3GB resident, held by a *background* process (the agent drives other apps by definition), making it a prime LMK kill target. Sustained inference throttles the SoC within minutes.

### Design: tiered routing

Fits the existing `LlmProvider` interface with zero rework — `LocalLlmProvider : LlmProvider` is a drop-in.

```
Tier 1  On-device intent router     ~instant, offline, no LLM
        "set an alarm for 6am" → set_alarm(06:00) → Intent → done

Tier 2  Local small LLM             unambiguous UI steps
        one obvious button, one text field

Tier 3  Cloud escalation            triggered by ANY of:
        low confidence · malformed JSON · ineffective action
        · step already failed locally once · sparse tree needing vision
```

Tier 1 alone covers a large share of realistic commands with zero network, and is by far the best effort-to-payoff ratio of the three.

**Tier 1 implementation — two options:**

- **Rules/regex** (fast to build) — pattern-match the top ~20 command shapes.
- **A trained classifier** (~1 week) — generate a few hundred labelled commands (`"set an alarm for 6am"` → `set_alarm{time:06:00}`), train an intent-classification + slot-extraction model, export to TFLite, run on-device.

The second option is the **only part of this project that constitutes genuinely training a model**, and it plugs the project's main gap: everything else is model *usage*, not model *building*. Worth doing if targeting AI/ML roles.

> **Scope honesty.** You are not building an LLM. Gemini is pretrained by Google and called over HTTPS; local mode runs Google's pretrained Gemma weights via MediaPipe. Running a model ≠ making one. What is yours: perception, compression, action schema, prompt loop, executor, state machine, and everything else in this document. Describe it that way — it survives follow-up questions, and overclaiming does not.

### Why this is worth doing

- **Privacy story.** In Offline Mode, screen contents never leave the device. For an app that can read your WhatsApp, this is not a footnote — it's the most defensible thing about the project.
- **An honest metric.** *"X% of steps resolved on-device; cloud escalation on the remaining Y%."* Measurable from the `steps` table, and far stronger than either "it's all local" or "it's all cloud."
- **It makes the resume claim true.** With this shipped, "on-device execution runtime with on-device-first reasoning and cloud escalation" is accurate.

---

## 5.6 Cost & development machine

### Money

Total expected cost: **₹0**, or under ~₹200 if careless. Nothing in the toolchain is paid.

| Item | Cost |
|---|---|
| Android Studio, JDK, Kotlin, Compose, Hilt, Ktor, Room, GitHub | Free |
| Gemini API free tier | Free, no card |
| Phone, laptop | Already owned |
| Server / hosting | None — there is no backend |

**Gemini free tier**, as of mid-2026: roughly 10–15 RPM and 250–1,500 requests/day on Flash (~1,000/day on Flash-Lite). Pro was removed from the free tier in April 2026. Verify current numbers at <https://ai.google.dev/gemini-api/docs/rate-limits> — Google has cut these before.

**1 agent step = 1 API request.** A task is 3–12 steps, so the free tier allows roughly 25–150 full test runs per day. The **per-minute limit binds before the daily one**, since the loop fires every few seconds.

Three cost traps:
- **Vision is expensive.** Screenshots cost far more than text — the conditional-attachment policy in §4.1 is a cost control as much as a quality one.
- **Runaway loops burn quota.** The step budget and loop detection in §4.5 are cost guards too.
- **Anthropic and OpenAI have no free tier.** Bill from request #1. Keep them as optional user-key paths, never the dev default.

**Privacy:** free-tier Gemini data may be used to improve Google's products. For an app that reads WhatsApp screens, make that a conscious choice — and note it's the strongest practical argument for M8's offline mode.

### Development machine

```
AMD Ryzen 5 4500U · 6 cores / 6 threads (no SMT)
7.4 GB RAM
C: 23.5 GB free   ← binding constraint
D: 231 GB free
```

Workable, because the physical test device means **the emulator is never needed** — and the emulator is what actually breaks 8 GB machines.

Required M0 setup adjustments:

1. **Everything on D:.** Android Studio, SDK, and Gradle cache. Defaults put the SDK in `C:\Users\gaura\AppData\Local\Android\Sdk` and caches in `C:\Users\gaura\.gradle`; combined these can exceed the 23.5 GB available on C:. Set `ANDROID_HOME` and `GRADLE_USER_HOME` to D: paths.
2. **Cap the Gradle heap** — `org.gradle.jvmargs=-Xmx2g` in `gradle.properties`, so the Gradle and Kotlin daemons don't starve the IDE.
3. **Enable** `org.gradle.caching=true`, `org.gradle.parallel=true`, and the configuration cache.
4. **Six modules, not twelve** (§3).
5. **Never install the emulator** — saves ~10 GB and a great deal of RAM contention.

Expect clean builds in the low minutes and incrementals under a minute.

---

## 6. Milestones

Each milestone ends in something runnable. Nothing is "done" without its acceptance test passing on the physical device.

### M0 — Environment
JDK 17, Android Studio + SDK 35, device in developer mode with USB debugging.
**Follow the D:-drive and heap-cap setup in §5.6 — C: does not have room for the defaults.** Skip the emulator entirely.
**Accept:** `adb devices` lists the phone; a stock Compose template runs on it.

### M1 — Skeleton + permissions
Multi-module Gradle, Hilt graph, design system, onboarding wizard, an `AccessibilityService` that logs foreground package changes.
**Accept:** enable the service in Settings, open WhatsApp, see `com.whatsapp` in Logcat.

### M2 — Perception
Tree walker, compressor, hoisting, screenshot capture. A debug screen dumping the live compressed JSON of whatever is behind the app.
**Accept:** on a WhatsApp chat list, ≥70% node reduction and ≥60% byte reduction, contact names all retained. **Record these numbers.**

### M3 — Execution (still no LLM)
Node resolver, fallback chains, gesture dispatcher, Intent registry. Debug screen with hand-typed actions.
**Accept:** manually issue `click(7)` and watch the correct WhatsApp chat open. `set_alarm` Intent creates a real alarm.

### M4 — Reasoning (single step)
`LlmProvider` interface, `GeminiProvider`, prompt builder, schema enforcement.
**Accept:** given a goal and a real screen, the model returns one valid, schema-conformant action that a human agrees is correct.

### M5 — The loop 🎯
Orchestrator state machine, verification, all guards, foreground service, retries.
**Accept:** **"set an alarm for 6:30am"** completes end-to-end, unattended, from voice-free text input. *This is the moment the project exists.*

### M6 — Voice + overlay
`SpeechRecognizer`, mic orb, floating bubble, streaming thoughts, element highlight, stop button.
**Accept:** speak "open Instagram and scroll down twice" and watch it happen with the thought bubble narrating.

### M7 — Polish + ship
History and replay, multi-provider settings, animation pass, empty/error states, README with GIF, 60-second demo video, signed APK on GitHub Releases.
**Accept:** a stranger can install the APK and run a task without you in the room.

### M8 — Offline Mode (stretch, only after M7)
On-device intent router (Tier 1), then MediaPipe + Gemma 3 as `LocalLlmProvider` (Tier 2), then the confidence-based cloud escalation policy (Tier 3). See §5.5.
**Accept:** with the device in airplane mode, "set an alarm for 6:30am" and "open Chrome" both complete. History shows the on-device vs escalated split.

> **Do not attempt M8 before M5 works.** A slow or wrong local model during a placement demo reads as broken, not ambitious. Cloud loop first; local as a toggle.

**Suggested order of demo tasks to harden against**, easiest first:
1. `set an alarm for 6:30am` (Intent path)
2. `open Chrome and search for Kotlin coroutines` (simple UI path)
3. `turn on airplane mode` (Settings navigation + toggle)
4. `send Rahul a WhatsApp message saying I'll be late` (multi-app, destructive gate fires)
5. `open Instagram and like the first post` (canvas-heavy — this is where vision fallback earns its keep)

---

## 7. Risks

### Field notes (found while building, not predicted)

| Discovery | Consequence |
|---|---|
| **Gemini 2.5 thinks by default, and thinking tokens count against `maxOutputTokens`.** | With a 512-token budget the model spent it reasoning and the JSON was cut off mid-string. It surfaces as `Unexpected EOF at $.summary`, which looks like malformed output rather than truncation - a genuinely misleading symptom. Fixed with `thinkingConfig.thinkingBudget = 0`; the ReAct `thought` field already carries the reasoning we want. |
| **Loop detection on repeated *actions* misses the common failure.** | The real oscillation was `HOME → NOTIFICATIONS → scroll → BACK → HOME → NOTIFICATIONS`: every action different, every step locally sensible, no progress. Detection has to key on the set of *screens* visited, not the actions taken. |
| **Flat retry against a per-minute rate limit makes it worse.** | A fixed 1.5s retry fired 13 times in 25 seconds and kept the quota pinned. Exponential backoff plus a retry ceiling. |
| **The notification shade is not drivable.** | ColorOS quick-settings tiles are custom-drawn and expose almost nothing to accessibility. The agent must be told to reach system toggles through the Settings app instead. |
| **Reinstalling unbinds the accessibility service** and Android does not reliably rebind it. | Pure dev-loop friction, but it looks exactly like an app bug. `run.ps1` force-toggles the service after every install. |
| **A stale service instance can clobber the live one.** | On reinstall the order is `old.onUnbind → new.onCreate → new.onServiceConnected → old.onDestroy`. An unconditional cleanup in the last step nulls the *live* handle. Teardown needs an identity guard. |

---

| Risk | Mitigation |
|---|---|
| **Play Store will not accept this** | Not a target. Sideloaded APK + GitHub is the deliverable. Know this before an interviewer asks — having the answer ready reads as maturity. |
| **WhatsApp/Instagram automation violates their ToS** | Demo on your own accounts only. Frame it as a research prototype, which it is. Never automate messaging strangers. |
| **OEM background-process killing** | Xiaomi/MIUI, Oppo/ColorOS, Vivo, Realme, OnePlus aggressively kill background services and can silently disable accessibility services after reboot or idle. Mitigation: request battery-optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — works), plus manual autostart instructions. Pixel/Samsung/Motorola/Nothing are unaffected. |
| **ColorOS autostart is unreachable in code** | Confirmed on the target device (OPPO CPH2761, ColorOS 16). The activities exist at `com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity`, but launching one throws `SecurityException: requires oplus.permission.OPLUS_COMPONENT_SAFE` — a signature-level OEM permission. No third-party app can open this screen. The onboarding shows manual instructions and no button, because a button that dumps the user on an unrelated page reads as a broken app. |
| **Android 16 warns about accessibility apps** | The system now shows a "Device may be at risk — review app with full device access" screen. Expected, not a bug, but know it before demoing: it appears right after the service is enabled. |
| Accessibility node staleness | Re-resolve strategy in §4.4 |
| Canvas-rendered apps expose no nodes | Conditional screenshot fallback in §4.1 |
| App UIs change and break flows | Nothing is hardcoded — the agent is generic by construction. This is the whole thesis. |
| LLM latency makes demos feel slow | Intent fast path, text-only steps by default, small/fast model tier, `wait` action instead of blind polling |
| API cost while iterating | Gemini free tier; cache identical (screen, goal) pairs during development |
| Infinite loops burning quota | Step budget + loop detection + wall clock, §4.5 |
| Scope creep sinking the timeline | M5 is the real finish line. M6–M7 are polish. Ship M5 before touching animations. |

---

## 8. Resume bullets — defensible versions

Write these only after M7, filling in **your actually-measured numbers** from the `steps` table. Every one of these survives a follow-up question.

- Built an autonomous Android GUI agent that executes natural-language tasks across arbitrary third-party apps with **no per-app integration**, using the AccessibilityService framework for perception and `dispatchGesture` for input synthesis.
- Cut per-step LLM payload by **~X%** (measured over N recorded steps) via a recursive tree-pruning compressor that discards non-interactive containers while hoisting their text into surviving ancestors — preserving semantic content at a fraction of the tokens.
- Designed a hybrid perception pipeline that sends text-only screen state by default and conditionally attaches a downscaled screenshot when the accessibility tree is sparse or an action proves ineffective, keeping canvas-rendered apps operable without paying vision-token cost on every step.
- Engineered a fault-tolerant execution runtime handling `AccessibilityNodeInfo` staleness across process boundaries through a re-resolution strategy (viewId → text → description → bounds) with a three-tier gesture fallback chain.
- Architected the agent as an MVI finite state machine with loop detection, step and wall-clock budgets, post-action verification via screen-signature hashing, and a user-confirmation gate on destructive actions.
- Kept the accessibility callback thread non-blocking by debouncing high-frequency `typeWindowContentChanged` events into a settle-detector flow, parsing the view hierarchy on demand rather than per event.
- Abstracted the reasoning layer behind a provider interface with schema-enforced structured outputs across Gemini, Anthropic, and OpenAI backends.

If M8 ships, add:

- Implemented a tiered inference router that resolves **X%** of agent steps entirely on-device (intent classifier + int4-quantized Gemma 3 via MediaPipe) and escalates to a cloud model only on low confidence, schema violation, or ineffective action — enabling a fully offline mode in which screen contents never leave the device.

**Do not claim:** "60 FPS on the main thread" (an AccessibilityService doesn't render) or "mastered Binder IPC" (you use it correctly — that's enough, and more credible). **"On-device LLM" is only claimable if M8 ships** — until then the reasoning is cloud, and an interviewer will ask.

---

## 9. Open questions for later

- Multi-turn conversation — should the agent remember the previous task's context?
- A learned/cached "app recipe" store, so a repeated task skips LLM calls entirely — a strong stretch feature, and a real research direction.
- Scoped memory of user preferences ("Rahul means the contact Rahul Sharma").

---

*Next step: M0 environment setup, then M1 skeleton.*
