# Google AI Studio Build Brief — Todd

Read `README.md` first. It is the product source of truth.

## Your task

Build Todd as a native Android application for one private owner.

Use:
- Kotlin
- Jetpack Compose
- AndroidX
- Room for persistent project/memory state
- Android Keystore-backed secure storage for secrets
- InputMethodService for the Todd keyboard
- AccessibilityService for semantic screen context where permission is granted
- MediaProjection only for explicit visual screen capture sessions
- a provider abstraction for AI so Todd is not locked to Gemini or any vendor

Do not turn Todd into a web-only app.
Do not make Firebase the canonical memory store.
Do not hard-code API keys or GitHub tokens.
Do not couple core business logic to a specific AI model.

## Architectural modules

Create clear boundaries for:

1. `app`
   - Compose UI
   - navigation
   - settings
   - permission status

2. `core-agent`
   - agent state machine
   - Plan / Execute / Verify / Record
   - task state and evidence

3. `core-memory`
   - Room entities/DAO/repository
   - projects
   - verified state
   - failures
   - next action

4. `core-ai`
   - `AIProvider` interface
   - local/cloud routing
   - mock provider for tests
   - no vendor-specific logic in agent core

5. `feature-overlay`
   - floating Todd button
   - drag handling
   - Hide target
   - Power Off target
   - state indicator

6. `feature-keyboard`
   - InputMethodService
   - normal keyboard use
   - Todd action
   - selected/current-field text handling
   - insert/replace generated result

7. `feature-screen`
   - Accessibility service
   - MediaProjection session controller
   - visible permission/activity indicators

8. `feature-github`
   - interface first
   - read-only implementation first
   - no embedded token

9. `feature-remote-agent`
   - interface/stub only in MVP
   - later handles long-running coding outside the phone

## First implementation target

Do NOT attempt the entire final product in one generation.

Build Milestone 1 only:

- Android app launches.
- Home screen shows Todd status.
- Floating button can be enabled.
- Floating button can be dragged.
- While dragging, two targets appear at the bottom:
  - Hide
  - Power Off
- Dropping on Hide removes the floating button but does not erase state.
- Dropping on Power Off sets Todd to disabled and stops starting new agent work.
- A notification or app screen can restore Todd.
- Room database persists a basic `ToddState` across app restarts.
- Add a minimal `AIProvider` interface plus a deterministic MockAIProvider.
- Add unit tests for state transitions.
- Add an Android build workflow in GitHub Actions.

## Required state model

At minimum:

```text
ToddRunMode:
  OFF
  PAUSED
  LOCAL_ONLY
  AUTO
  CLOUD_ACTIVE

TaskState:
  PLANNED
  IN_PROGRESS
  EXECUTED
  VERIFIED
  FAILED
  BLOCKED
```

Never map EXECUTED to VERIFIED automatically.

## Verification gates

Before claiming Milestone 1 complete:

1. Run unit tests.
2. Run Gradle assembleDebug.
3. Confirm APK task completes.
4. Confirm state transition tests cover Hide vs Power Off behavior.
5. Confirm no API key/token exists in tracked source.
6. Record exact command/result in `PROJECT_STATE.md`.

If a gate fails:
- record failure;
- fix only the verified cause;
- re-run the failed gate;
- do not claim success until it passes.

## Coding rules

- Preserve working code.
- Small, reviewable changes.
- Do not redesign architecture unless a proven technical blocker requires it.
- Do not silently replace native Android with a web wrapper.
- Do not add a cloud dependency just to make the demo easier.
- Every permission must have a clear user-facing explanation.
- Keep Arabic UI compatibility in mind from the beginning, including RTL.

## After Milestone 1

Stop and report:
- commit(s);
- tests run;
- build result;
- known limitations;
- next proposed milestone.

Milestone 2 will add the real keyboard service and local AI routing.
Milestone 3 will add screen awareness.
Milestone 4 will add GitHub read access.
Milestone 5 will add a real cloud AI provider.
Milestone 6 will add long-running remote coding.

Do not skip directly to later milestones.
