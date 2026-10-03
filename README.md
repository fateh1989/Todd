# Todd — Personal Hybrid AI Agent

Todd is a private Android-first personal AI agent inspired by the idea of an always-available assistant. It is designed for one owner only and must be **local-first, provider-agnostic, verifiable, and resumable**.

## Product goal

Todd should not be just a chatbot. It should act as a persistent personal agent that:
- remembers projects and the last verified state;
- can continue long-running work instead of restarting from zero;
- can work from an Android keyboard and a floating overlay;
- can understand the current screen only when the owner explicitly grants permission;
- can use local AI for private/light tasks and cloud AI for heavy tasks;
- can connect to GitHub and work on repositories with explicit permissions;
- can run long coding workflows through a remote/cloud execution layer;
- records what was planned, executed, verified, failed, and what should happen next.

## Core design principles

1. **Local-first:** memory, project state, settings, permissions, routing, and basic intelligence should remain on-device where practical.
2. **Provider-agnostic:** the app must not depend on Gemini, OpenAI, Anthropic, or any single vendor. Cloud models are interchangeable providers behind one interface.
3. **Explicit permissions:** screen reading, accessibility control, microphone, files, GitHub, cloud AI, and other tools must be separately controllable.
4. **Verification before claims:** Todd must never report that a task is fixed, built, uploaded, tested, or complete unless a real tool result or test proves it.
5. **Persistent state:** each project stores the last verified commit, branch, task, failures, test results, and next step.
6. **Safe stop controls:** the user must always be able to pause, hide, or fully stop Todd.

## Android experience

### Floating Todd button
- Small floating circular button available above other apps when permission is granted.
- User can drag it anywhere.
- When dragged, two bottom targets appear:
  - **Hide**: hide only the floating button while Todd continues allowed background work.
  - **Power Off**: stop Todd from starting new work and stop its active agent loop safely.
- A short hold over the Power Off target prevents accidental shutdown.
- Button state should visibly distinguish: idle, local-only, cloud-active, working, paused, and error.
- Restore the button from a persistent notification, app screen, or Todd keyboard.

### Todd keyboard
Implement a real Android IME using `InputMethodService`.
The keyboard should support:
- normal typing first;
- Todd action button;
- correct / rewrite / translate / summarize / reply / explain;
- access to selected text and text around the cursor when Android permits it;
- ability to return generated text into the current input field;
- quick switch between normal keyboard mode and AI mode.

### Screen awareness
Use two permission-based paths:
- `AccessibilityService` for semantic UI text/elements where available;
- `MediaProjection` for an explicit screen capture session when visual understanding is required.

Todd must clearly indicate when screen access is active. It must not assume it can read protected or inaccessible content.

## AI architecture

Create a common `AIProvider` interface so providers can be swapped without changing core logic.

Suggested providers:
- Local on-device model through LiteRT / LiteRT-LM or another Android-compatible runtime.
- Gemini provider.
- OpenAI provider.
- Additional providers can be added later.

Routing logic:
- local model for simple/private/low-cost work;
- cloud model for complex reasoning, coding, large context, or web-connected tasks;
- manual modes: Local Only / Auto / Cloud Preferred.

Cloud free tiers are optional bonuses, never a dependency.

## Memory and project state

Keep the primary memory on-device.

Each project should persist:
- project name;
- repository;
- current branch;
- last verified commit;
- current goal;
- last executed change;
- last test/check result;
- known failures;
- failed approaches that should not be repeated without a changed technical factor;
- next action;
- concise project summary;
- tool permissions.

Use a local database (Room recommended) and encrypt sensitive data using Android Keystore-backed mechanisms.

## Agent execution model

Todd follows a state machine:

PLAN → EXECUTE → VERIFY → RECORD → CONTINUE / STOP

Every meaningful action creates a state record.

Todd must distinguish:
- Planned
- In progress
- Executed
- Verified
- Failed
- Blocked

If verification fails, do not claim success.

## Long-running coding

Long coding work must not depend on keeping the Android app alive for hours.

Phone responsibilities:
- issue commands;
- show progress;
- hold local state;
- approve sensitive actions;
- reconnect to long-running jobs.

Remote agent responsibilities:
- checkout the selected repository/branch;
- inspect current state;
- edit files;
- run builds/tests/checks;
- read failures;
- retry only when a technical factor changed;
- commit verified steps;
- return structured progress and evidence.

The remote execution layer must be abstracted so it can later use different cloud/agent providers.

## GitHub integration

Use OAuth / GitHub App style authorization with minimum required permissions.
Never hard-code a GitHub token in the APK.

Initial capabilities:
- read repository;
- read branch/commit status;
- read workflow/check results;
- create/update files;
- create commits;
- optionally create branches and pull requests;
- never merge/delete/force-push without explicit owner permission.

## Privacy and secrets

- No API key hard-coded in source or APK.
- Store secrets with Android Keystore-backed secure storage.
- Do not upload full memory or files to cloud by default.
- Send only the context required for the active task.
- Every provider and tool must have an independent on/off permission.

## First usable milestone (MVP)

Build and verify in this order:

1. Android app opens successfully.
2. Floating Todd button works and can be dragged.
3. Dragging shows Hide and Power Off targets and each behaves correctly.
4. Basic chat screen works.
5. Local Room memory survives app restart.
6. AI provider abstraction exists with a mock provider.
7. One real cloud provider can be configured without hard-coded secrets.
8. Todd keyboard appears in Android keyboard settings and can type normally.
9. AI action can read selected/current-field text where Android allows it and write the result back.
10. Screen awareness permissions and visible status work.
11. GitHub read-only connection works.
12. Project state can save repository, branch, commit, last verification, failure, and next step.

Only after these are verified should long-running remote coding be added.

## Repository

This repository is the canonical source for Todd.
Default branch: `main`.

Google AI Studio or another coding agent should **extend this repository incrementally**, preserve verified working parts, and run the relevant build/tests after every significant change.
