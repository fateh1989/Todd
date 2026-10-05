# Google AI Studio — Product Brief for Todd

Read `README.md` and `PROJECT_STATE.md` first. This file defines the intended product experience.

## Product vision

Build **Todd** as a private, always-available personal AI companion for Android. It is not a chat app with a few AI buttons. It should feel like a persistent intelligent assistant that lives with the user across apps, remembers ongoing work, understands context when permission is granted, speaks naturally, and can continue multi-step tasks over time.

Todd is designed for **one owner only**. It should be deeply personal, simple to invoke, easy to stop, privacy-conscious, and able to grow into a long-running software agent.

The experience should feel continuous:
- the user should not have to re-explain a project every time;
- Todd should know the last verified state of each project;
- it should distinguish between planning, executing, verifying, failing, and finishing;
- it should be able to resume a task after the app is closed or reopened;
- it should return to the user when it has a verified result, needs permission, or reaches a blocker.

## Main interaction model

Todd should be available through three primary surfaces:

### 1. Floating Todd button
A small elegant floating circular button above other apps, when overlay permission is granted.

Behavior:
- tap: open the compact Todd assistant;
- drag: move it anywhere on screen;
- while dragging, show two large targets at the bottom:
  - **Hide** — hide the floating button only;
  - **Power Off** — stop Todd from starting new work and safely stop the active agent loop;
- require a short hold over Power Off before shutdown to prevent accidental stopping;
- allow restoration from the app, persistent notification, or Todd keyboard;
- visibly represent state: idle, listening, thinking, working, local-only, cloud-active, paused, error.

The floating control should be minimal, polished, and non-intrusive.

### 2. Todd keyboard
Implement a real Android keyboard using `InputMethodService`.

The keyboard must remain fully usable as a normal keyboard first, then add Todd intelligence on top.

Core actions:
- correct;
- rewrite;
- translate;
- summarize;
- explain;
- reply;
- continue writing;
- send selected/current text to Todd;
- insert or replace generated text back into the active field.

Todd should be callable directly from the keyboard without opening the main app.

### 3. Voice conversation
Todd must support natural two-way voice interaction.

The user should be able to:
- press a microphone button and speak;
- start a continuous voice conversation session;
- interrupt Todd while it is speaking;
- hear Todd reply naturally;
- switch between Arabic and English;
- choose or change the voice later.

Voice should be optional. Todd must not continuously listen unless the user explicitly activates a voice session.

## Screen awareness

Todd may understand the current screen only with explicit permission.

Use two complementary mechanisms:

### Semantic screen context
Use `AccessibilityService` to read accessible UI text, controls, labels, and structure where Android allows it.

### Visual screen context
Use `MediaProjection` only when actual visual understanding is needed and after explicit user consent for the session.

Todd must:
- visibly indicate when screen access is active;
- never claim it can see protected or unavailable content;
- prefer semantic UI context over screenshots when sufficient;
- avoid sending full screen content to cloud unless required for the active task.

Example experience:
The user opens WhatsApp, a browser, GitHub, or another app, invokes Todd, and asks:
- “Explain what is on this screen.”
- “Reply to this.”
- “Translate this.”
- “What should I do next?”
Todd uses only the context that Android and the user allow.

## Persistent personal memory

Todd must remember ongoing work across sessions.

The canonical memory should be local-first and stored on-device using Room.

For every project, persist:
- project name;
- purpose;
- current goal;
- repository;
- current branch;
- last verified commit;
- last executed change;
- last successful test/build;
- known failures;
- approaches that failed previously;
- known reason for each failure;
- next action;
- concise project summary;
- user-specific rules;
- tool permissions.

Todd must retrieve only the relevant memory for the current task instead of loading the entire history every time.

## Project agents

Todd should support multiple persistent project agents inside one app.

Examples:
- coding project agent;
- research agent;
- GitHub monitoring agent;
- writing agent;
- personal task agent.

Each project agent has its own:
- memory;
- state;
- tools;
- permissions;
- current task;
- execution history.

They all share the same Todd interface.

## Agent behavior

Todd must behave like an execution agent, not a text completion bot.

Use this state loop:

```
PLAN
→ EXECUTE
→ VERIFY
→ RECORD
→ CONTINUE / STOP / ASK
```

Required task states:
- PLANNED
- IN_PROGRESS
- EXECUTED
- VERIFIED
- FAILED
- BLOCKED

Rules:
- EXECUTED is never automatically VERIFIED.
- Never claim “fixed”, “built”, “uploaded”, “working”, or “complete” without a real tool result or test.
- After a meaningful change, verify before continuing.
- If an approach failed, record it and do not retry the same approach unless a specific technical factor changed.
- Preserve working parts.
- Prefer the smallest verified change over broad rewrites.
- When two sources or results conflict, record the conflict instead of choosing the convenient answer.

## Long-running software work

Todd should be able to manage coding work that lasts for hours, but the Android phone should not be the machine that must stay awake and compile continuously.

Phone responsibilities:
- issue commands;
- show progress;
- hold personal state;
- grant permissions;
- display logs and evidence;
- reconnect to a long-running job.

Remote execution responsibilities:
- clone or checkout the selected repository;
- inspect the current branch and commit;
- modify files;
- run tests/builds;
- inspect failures;
- apply a targeted fix;
- rerun verification;
- create commits for verified steps;
- return structured evidence to Todd.

The remote execution layer must be an interface, not tied permanently to one vendor.

## GitHub integration

Todd should connect to GitHub through secure authorization with minimum required permissions.

Capabilities should be added progressively:
- read repository;
- read branches and commits;
- read checks/workflows;
- inspect logs;
- create/update files;
- create commits;
- create branches and pull requests later.

Sensitive operations such as delete, force-push, merge, repository removal, or destructive actions must require explicit approval.

Never hard-code GitHub tokens in the APK.

## Hybrid AI design

Todd must be **local-first and provider-agnostic**.

Create one common `AIProvider` interface.

Potential providers:
- local Android model;
- Gemini;
- OpenAI;
- other providers added later.

Routing modes:
- **Local Only**
- **Auto**
- **Cloud Preferred**

Automatic routing concept:
- simple/private/low-cost request → local model;
- complex coding/large-context/reasoning request → cloud model;
- user can override the route at any time.

The product must never depend on a free cloud tier for survival. If one provider changes pricing or disappears, Todd should continue working and another provider should be swappable in.

## Privacy

Todd is for one owner and should minimize unnecessary data movement.

Requirements:
- primary memory stays local;
- secrets use Android Keystore-backed secure storage;
- no API key or token is committed to Git or embedded directly in source;
- cloud providers receive only the minimum context needed;
- each tool/provider has a separate permission toggle;
- the user can disable screen access, cloud AI, microphone, GitHub, or the entire agent independently.

## Main app

The main app should be simple and polished, with Arabic RTL support from the beginning.

Recommended home screen:
- Todd status;
- current active project;
- current task;
- last verified action;
- floating button toggle;
- voice button;
- Local / Auto / Cloud mode;
- permissions summary;
- recent activity;
- projects;
- settings.

Avoid developer-looking clutter in the user interface.

## Shutdown and control

Todd must always be easy to stop.

Controls:
- drag floating button to **Hide**;
- drag floating button to **Power Off**;
- pause Todd;
- switch to Local Only;
- disable screen awareness;
- disable voice;
- disable cloud access;
- stop one project agent without stopping the entire app;
- Android system permissions remain the final authority.

Power Off should stop new agent work safely, persist current state, and clearly show that Todd is off.

## Technical baseline

Use:
- Kotlin;
- Jetpack Compose;
- AndroidX;
- Room;
- Android Keystore-backed secure storage;
- InputMethodService;
- AccessibilityService;
- MediaProjection;
- WorkManager where appropriate for resumable local coordination;
- interfaces around remote/cloud services.

Do not build Todd as a web wrapper.
Do not make Firebase the canonical source of personal memory.
Do not tie core logic to Gemini or any one AI vendor.

## Build strategy

The **entire product specification in this document is binding from the first day**. Do not treat later capabilities as optional, future ideas, or undefined scope. Todd must be architected from the beginning to support the complete product described above.

Implementation may be divided into verified steps only to reduce breakage and make testing reliable. These steps are an execution order, **not a staged definition of the product**. Do not simplify the architecture in an early step in a way that blocks keyboard, voice, screen awareness, hybrid AI, GitHub, persistent agents, or long-running coding later.

### Milestone 1 — Foundation
Build and verify:
- native Android project;
- main Todd screen;
- draggable floating button;
- Hide target;
- Power Off target;
- persistent Todd state using Room;
- `AIProvider` interface;
- deterministic mock AI provider;
- state-machine unit tests;
- GitHub Actions Android build workflow.

### Milestone 2 — Keyboard and local intelligence
- real Android IME;
- normal typing;
- Todd actions;
- local AI runtime/provider;
- text insertion/replacement.

### Milestone 3 — Voice and screen awareness
- microphone input;
- spoken replies;
- continuous voice session;
- Accessibility context;
- MediaProjection visual session;
- clear privacy indicators.

### Milestone 4 — GitHub
- secure authorization;
- repository read;
- commits/branches/checks;
- project-state linking.

### Milestone 5 — Cloud AI
- one real cloud provider;
- routing;
- usage controls;
- replaceable provider architecture.

### Milestone 6 — Long-running coding agent
- remote execution interface;
- repository checkout;
- edit/build/test loop;
- progress;
- reconnect/resume;
- verified commits.

## Milestone 1 verification gates

Before claiming Milestone 1 complete:
1. run unit tests;
2. run Gradle `assembleDebug`;
3. confirm the APK task completes;
4. verify Hide and Power Off produce different persisted states;
5. verify state survives app restart;
6. scan tracked source for API keys/tokens;
7. record exact commands and results in `PROJECT_STATE.md`.

If any gate fails, record the failure and fix only the verified cause before continuing.

## Expected feel

Todd should feel like a personal intelligent presence on the phone:
- always reachable but never intrusive;
- remembers what matters;
- speaks naturally;
- understands the current context when allowed;
- continues real work instead of only discussing it;
- can work locally or use a stronger cloud brain;
- can manage long-running coding work;
- can be stopped instantly;
- never pretends success without evidence.

Build the product around this behavior, not around a chat screen.


---

## Current continuation rules — 2026-10-05

These rules override older implementation assumptions when they conflict with the current repository.

1. Continue the existing Kotlin/Compose Android architecture. Do not restart Todd from scratch and do not replace it with a web wrapper.
2. The owner manually chooses the cloud model according to need:
   - Gemini 3.5 Flash-Lite for frequent/light work.
   - Gemini 3.8 Flash for coding/difficult work.
   Todd must not silently switch between them. Persist the owner's choice and use it for normal cloud text and autonomous coding.
3. Preserve direct Gemini text chat. It already works without Firebase and uses the owner's locally stored Gemini API configuration.
4. Preserve the direct Gemini Interactions tool path for Google Search, Android/device context, repository/workflow inspection, repository file listing/reading, autonomous coding, scheduling, task status and remote-job control.
5. The existing voice path is separate and still requires real-device completion/verification. Do not regress text chat while fixing voice.
6. Stable Android update signing is a release blocker. Before the next long-term owner-installable APK baseline, configure one permanent signing identity and verify it. Every later update must use the same identity so app data survives upgrades.
7. Physical-device evidence currently reaches build 81 for real Gemini text chat and enabled Accessibility. Newer repository features are CI verified only until installed and tested on the owner's device.
8. Follow PLAN → EXECUTE → VERIFY → RECORD. Green compilation proves buildability, not real-device behavior.
