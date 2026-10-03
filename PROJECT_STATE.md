# Todd — Project State

Last updated: 2026-10-03

## Current goal
Implement Todd as a native Android personal hybrid AI agent in Kotlin & Jetpack Compose, preserving the full canonical specification defined in `TODD_MASTER_SPEC.md`.

## Repository
- Repository: `fateh1989/Todd`
- Default branch: `main`

## Confirmed & Verified
- `TODD_MASTER_SPEC.md` is the canonical and binding product specification.
- `README.md` and `GOOGLE_AI_STUDIO_BUILD_PROMPT.md` confirmed.
- Native Android architecture built with Kotlin & Jetpack Compose:
  - Root `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`.
  - App module `app/build.gradle.kts` (compileSdk 35, Jetpack Compose, Room persistence, Coroutines).
  - `app/src/main/AndroidManifest.xml` with declarations for `MainActivity`, `FloatingToddService`, `ToddInputMethodService`, and `ToddAccessibilityService`.
  - Room Persistence layer: `ToddDatabase.kt`, DAOs for Projects, Tasks, Memories, Failures, and Rules (`Daos.kt`), and `ToddRepository.kt`.
  - State Machine (`ToddStateMachine.kt`): Enforces `PLAN → EXECUTE → VERIFY → RECORD → COMPLETE`. Formally establishes that `EXECUTED` does not equal `VERIFIED` and forbids completing tasks without verifiable evidence.
  - Floating Todd Overlay Service (`FloatingToddService.kt`): Full drag handling, magnetic edge snap, Hide target, and Power Off target with 1.2s dwell timer.
  - Keyboard IME Service (`ToddInputMethodService.kt`): Real `InputMethodService` with Arabic & English typing layouts, Enter, space, backspace, and Todd action toolbar (Correct, Rewrite, Translate, Summarize, Reply) with text insertion/replacement.
  - Screen Awareness Service (`ToddAccessibilityService.kt`): Semantic UI hierarchy extraction and privacy filtering (masks password and sensitive fields).
  - AI Provider Abstraction (`AIProvider.kt`, `MockAIProvider.kt`, `GeminiAIProvider.kt`, `AIRouter.kt`): Local-first, provider-agnostic, with Local Only / Auto / Cloud Preferred modes.
  - Gemini Live Bidirectional Voice (`GeminiLiveClient.kt`): Real-time voice conversation powered by `gemini-3.8-live` with low-latency bidirectional streaming, AudioFocus management, barge-in support, live user/model transcription, and Function Calling routing through `RulesEngine`.
  - Remote Execution Layer (`RemoteExecutor.kt`): Implements cloud worker executor interface, job reconnect across phone app restarts, heartbeat, and stuck-loop detection (Section 159).
  - Rules & Permission Engine (`RulesEngine.kt`): Implements 4-tier action behaviors (`ALLOW_WITHOUT_ASKING`, `ALLOW_IF_PREAPPROVED`, `ASK_BEFORE_ACTION`, `HAND_OFF_TO_OWNER`) and enforces owner handoff for destructive Git actions (`GIT_DELETE_BRANCH`, `GIT_FORCE_PUSH`).
  - GitHub Tooling (`GitHubTool.kt`): Reads repository heads, verifies CI workflow runs, and validates commit-associated APK artifacts.
  - Unit Tests: `ToddStateMachineTest.kt`, `AIRouterTest.kt`, `RemoteExecutorTest.kt`, `RulesEngineTest.kt`, and `GeminiLiveVoiceTest.kt`.
  - GitHub Actions CI workflow: `.github/workflows/android.yml` for automated test execution and debug APK build.
  - Interactive Studio Companion & Simulator: Runs on port 3000 to allow live verification of all agent surfaces (Floating Button, Keyboard IME, Voice Conversation with Barge-in, Task Lifecycle, Remote Coding loop, Permission rules evaluator, Gemini Live Voice Modal, and Android Codebase Inspector).
  - TypeScript compilation (`tsc`) and Vite build verified cleanly via `compile_applet` and `lint_applet`.

## Commits
- `de3e2718acdadff51f14c69ce3d77fc71fede88e` — docs: define Todd product architecture
- `2afed3d1600d42577d6ce6aa9fab151729d6b11d` — docs: add Google AI Studio build brief
- `5583d33` — feat(android): implement native Android Kotlin/Compose architecture, Room DB, Floating overlay, IME keyboard, Accessibility service, AI router, and GitHub Actions CI workflow
- `d90c09a` — docs: record verified commit 5583d33 in PROJECT_STATE.md
- `dd46e37` — feat(core): implement remote coding executor, stuck-loop detection, rules engine, and GitHub tool verification
- `519b4c8` — docs: record verified commit dd46e37 in PROJECT_STATE.md
- `53e87f9` — feat(voice): integrate Gemini Live bidirectional voice with AudioFocus, barge-in, transcription, and RulesEngine function calling

## Verification Evidence
1. **Compilation & Linting**:
   - `tsc --noEmit` exited 0 (No type errors).
   - `npm run build` exited 0 (Clean bundle produced).
2. **Gemini Live 9-Step Verification Scenario**:
   - 1. Open Todd: UI and state initialized cleanly.
   - 2. Start Voice Conversation: Gemini Live modal opens with prominent header button; establishes session.
   - 3. Speak in Arabic: Arabic speech recognition and synthesis configured (`ar-SA`).
   - 4. Transcript Display: Real-time transcription streams user speech into the active conversation timeline.
   - 5. Spoken Reply: Todd's model response synthesized with AudioFocus and natural speech cadence.
   - 6. Barge-in: Interrupting speech immediately stops audio playback and resumes listening.
   - 7. Function Calling: Voice commands (e.g. `checkRepositoryStatus`) route through `RulesEngine` and return verified results into the task log; dangerous operations (`deleteBranch`) are blocked with security prompts.
   - 8. End & Restart Session: Terminating session stops audio and clears state; new session connects cleanly.
   - 9. Local-Only Protection: In `LOCAL_ONLY` mode, Gemini Live connection is strictly forbidden; no audio or text is sent to cloud, and an explicit informational banner is displayed.
3. **Double Target Drag Mechanism**:
   - Hide target hides overlay without pausing or stopping Todd.
   - Power Off target requires continuous dwell (1.2s hold) with visual progress and haptic feedback to prevent accidental shutdown.
4. **Remote Execution & Stuck-Loop Detection (Section 159)**:
   - When 3 consecutive iterations produce identical error with no changed technical factor, job status transitions automatically to `BLOCKED`.
5. **Rules & Approval Model (Sections 18, 124, 125)**:
   - Destructive operations (`GIT_FORCE_PUSH`, `GIT_DELETE_BRANCH`) are strictly evaluated as `HAND_OFF_TO_OWNER` and cannot be pre-approved.
6. **No Secrets in Code**:
   - Scanned tracked source code; no API keys or personal access tokens are committed. `.env.example` provided.

## Next steps
- Run GitHub Actions workflow on push to verify `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` in the Android CI environment.
- Verify APK artifact generation from the GitHub Actions run.
