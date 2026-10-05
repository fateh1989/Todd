# Todd — Project State

Last verified update: 2026-10-05

## Current goal
Build Todd as a native Android-first persistent personal AI agent that can converse by text/voice, understand the current phone context broadly, remember projects, use GitHub tools, research with live grounding, perform autonomous coding, and hand long-running build/test work to a reconnectable remote execution layer.

## Repository
- Repository: `fateh1989/Todd`
- Default branch: `main`
- Current verified head: `acdbcca2f6a0a2697ad6e8d3d656df7b7df2ea8c`
- Verified commit message: `feat(voice): control autonomous Todd tasks from Gemini Live`

## Verified Android build evidence
- GitHub Actions workflow: `Todd Android CI`
- Run: `37257908583`
- Run number: `76`
- Head SHA: `acdbcca2f6a0a2697ad6e8d3d656df7b7df2ea8c`
- Unit tests: success
- Debug APK assembly: success
- APK existence check: success
- APK signature verification: success
- Artifact upload: success
- Artifact: `app-debug`
- Artifact ID: `11323871004`
- Artifact size: 42,042,575 bytes
- Artifact SHA-256 digest reported by GitHub: `7742d18dea1d7c55ebe3f0de6dd999a89dcded453215b2c341f6cb326af261f5`

## Verified remote-execution evidence
- Remote worker workflow: `Todd Remote Worker`
- Self-test run: `37241987569`
- Commit tested: `c8d3a689a4f5c5be9d532b98934526dbdc63c7e2`
- Checkout: success
- Exact requested-start-commit verification: success
- Remote Android test/build task: success
- Evidence collection: success
- Artifact upload: success
- Artifact: `todd-remote-self-test`
- Artifact ID: `11317828513`

## Implemented and currently present in main
- Native Android Kotlin / Jetpack Compose application.
- Room persistence for projects, tasks, memories, failures, and rules.
- Persistent chat and project-context retrieval.
- Explicit durable preference learning through `MemoryLearningEngine`.
- Selectable persistent Todd projects with repository/branch association and active-project restoration.
- AI routing modes: Local Only / Auto / Cloud Preferred.
- Firebase AI Logic cloud Gemini provider using `gemini-3.8-flash`.
- Runtime Firebase configuration imported from `google-services.json` and stored encrypted with Android Keystore.
- Firebase configuration can be pasted or selected directly from Android's file picker without rebuilding the APK.
- Real cloud-connectivity self-test in Settings.
- Gemini on-device provider using the dedicated `firebase-ai-ondevice` runtime and strict `ONLY_ON_DEVICE` inference mode.
- Settings controls that report Firebase/runtime status and let the owner check/prepare the on-device model.
- Real Gemini Live integration using `gemini-3.1-flash-live-preview`, live audio APIs, transcription, interruption support, function calling, and live visual frame streaming while screen capture is active.
- Gemini Live can start autonomous coding tasks and read durable Todd task progress/evidence for the active project.
- GitHub REST read/write tooling with Android Keystore-backed encrypted credential storage.
- GitHub repository inspection, file reading, workflow reading, and real commit creation tools.
- Persistent GitHub Actions-backed remote execution with reconnect, cancellation, exact start-commit verification, Android tests/builds, and artifact evidence.
- Remote task status synchronization into Room plus Refresh/Cancel controls in the Activity screen.
- Model-driven autonomous coding loop:
  - inspect repository tree,
  - select/read relevant files,
  - generate complete-file edits,
  - create real commits,
  - run remote Android verification,
  - consume real GitHub Actions failure evidence,
  - retry from the observed failure,
  - create new source/config text files when needed.
- Durable autonomous coding checkpoints plus WorkManager recovery after process death/restart.
- Main text chat has real tool calling and can inspect GitHub, start autonomous coding, check tasks, and reconnect/cancel remote jobs.
- Accessibility semantic screen extraction.
- Continuous MediaProjection screen capture.
- ML Kit local OCR extraction fused into device context.
- Fused device context using accessibility, visual capture, OCR, notifications, keyboard context, and app/window metadata.
- Notification listener context.
- Todd IME keyboard with Arabic/English typing, Enter, centered space, backspace, compact top bar, feature drawer, clipboard, project notes, and AI writing/research actions.
- Floating Todd button with drag/snap, hide/power targets, and compact assistant controls.
- Live Google Search grounding for cloud research responses, with grounded source metadata preserved in memory/chat.
- One-tap in-app diagnostics for Room, Firebase/cloud AI, local AI, GitHub, accessibility, visual screen capture, overlay, microphone, notification access, and keyboard enablement.
- CI supports monotonic versionCode/versionName using GitHub run number.
- CI supports optional stable APK signing through repository secrets and verifies the produced APK signature with `apksigner`.

## Toolchain verified by current green build
- Kotlin: 2.3.21
- Android Gradle Plugin: 8.10.0
- KSP: 2.3.9
- Room: 2.8.5
- Gradle wrapper: 8.11.1
- Firebase AI on-device runtime: 16.0.0-beta05
- WorkManager: 2.12.0

## Important technical distinction
The repository has verified compile/test/APK evidence and a verified remote build/test worker. This does not by itself prove every device-dependent capability works on the owner's physical Android device.

## Screen-awareness direction
Todd should read and understand as much of the phone screen/context as technically available when the feature is enabled. It combines AccessibilityService, MediaProjection, visual analysis, local OCR, InputConnection, notifications, clipboard context when used, and app/window metadata. If one source has no data, the others remain available. Todd does not add an internal app/content blocklist; actual Android/OS API boundaries remain technical limits.

## Stable signing state
The stable-signing infrastructure is implemented, but the stable signing secrets are not currently configured in GitHub. When absent, GitHub Actions falls back to the standard Android debug signing key. Therefore the build is signature-verified, but a long-term stable production/update signing identity is not yet verified.

## Remaining verification / implementation gaps
1. Install the latest APK on the owner's Android device and run the in-app diagnostic suite.
2. Import the owner's real Firebase configuration in the app (or provide the real CI secret) and verify a real cloud Gemini request on the physical device.
3. Verify Gemini Live microphone/audio, interruption, screen streaming, autonomous-coding voice tools, and GitHub function calls on the physical device.
4. Verify on-device Gemini support, model preparation/download, local text/image response, and strict no-cloud behavior on the physical device.
5. Verify Android permission flows, Todd keyboard ergonomics, clipboard behavior, overlay drag/hide/power behavior, notification access, accessibility context, MediaProjection, OCR, and device-context fusion on the physical device.
6. Add and verify a long-term stable signing key through the four Todd signing secrets if seamless APK upgrades are required.
7. Keep documentation synchronized only with evidence actually obtained.

## Verification rule
Do not mark a feature as verified merely because source code exists. Mark it verified only when the relevant test, GitHub Actions evidence, tool result, or real-device behavior proves it.
