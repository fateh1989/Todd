# Todd — Project State

Last verified update: 2026-10-04

## Current goal
Build Todd as a native Android-first persistent personal AI agent that can converse by text/voice, understand the current phone context broadly, remember projects, use GitHub tools, research with live web grounding, and hand long-running build/test work to a reconnectable remote execution layer.

## Repository
- Repository: `fateh1989/Todd`
- Default branch: `main`
- Current verified head in this record: `f872d82dd1c920f04739137e239d5381fcf0ff37`

## Verified build evidence
- GitHub Actions workflow: `Todd Android CI`
- Run: `37242340340`
- Head SHA: `f872d82dd1c920f04739137e239d5381fcf0ff37`
- Unit tests: success
- Debug APK assembly: success
- APK existence check: success
- Artifact upload: success
- Artifact: `app-debug`
- Artifact ID: `11316924073`
- Artifact size: 20,009,561 bytes
- Artifact SHA-256 digest reported by GitHub: `c04150c1868128ad753ec14477b7aba79e1f6312483280314b1cd21e061282ed`

## Verified remote-execution evidence
- Remote worker workflow: `Todd Remote Worker`
- Self-test run: `37241987569`
- Commit tested: `c8d3a689a4f5c5be9d532b98934526dbdc63c7e2`
- Checkout: success
- Requested-start-commit verification: success
- Remote Android test/build task: success
- Evidence collection: success
- Artifact upload: success
- Artifact: `todd-remote-self-test`
- Artifact ID: `11317828513`

## Implemented and currently present in main
- Native Android Kotlin / Jetpack Compose application.
- Room persistence for projects, tasks, memories, failures, and rules.
- Persistent chat/project context retrieval.
- Explicit durable preference learning through `MemoryLearningEngine`.
- AI routing modes: Local Only / Auto / Cloud Preferred.
- Firebase AI Logic cloud Gemini provider.
- On-device Gemini provider path with local-only enforcement when supported by the device.
- Real Gemini Live session integration with live audio conversation APIs, transcription, interruption support, function calling, and live visual frame streaming while screen capture is active.
- GitHub REST read/write tooling with Android Keystore-backed encrypted credential storage.
- GitHub file inspection and real commit creation tools.
- A persistent remote execution abstraction backed by GitHub Actions, reconnectable by run ID and stored locally.
- Remote worker self-test workflow that verifies exact start commit, runs tests/builds, and uploads evidence.
- Accessibility semantic screen extraction.
- Continuous MediaProjection screen capture.
- Fused device context using accessibility, visual capture state, notifications, and keyboard context.
- Notification listener context.
- Todd IME keyboard with Arabic/English typing, Enter, space, backspace, and AI writing actions.
- Floating Todd button with drag/snap and compact control panel.
- Live Google Search grounding for cloud research responses, with grounded source metadata preserved in memory/chat.

## Important technical distinction
The repository now has verified compile/test/APK evidence and a verified remote build/test worker. That does not by itself prove every user-facing feature works correctly on a physical Android device. Device-level verification is still required for microphone/audio behavior, real Firebase configuration, on-device Gemini availability on the owner's device, Android permission flows, keyboard ergonomics, overlay behavior across OEMs, and broad screen-awareness behavior.

## Screen-awareness direction
The owner's current instruction is that Todd should read and understand as much of the phone screen/context as technically available when the feature is enabled. Todd should combine AccessibilityService, MediaProjection, visual analysis, OCR where useful, InputConnection, notifications, clipboard context when enabled, and app/window metadata. If one source fails, it should fall back to the others. Todd should not add artificial app/content blocklists; actual Android/OS API limits are technical limits only.

## Known remaining gaps / next work
1. Verify and provide a real runtime Firebase configuration for the installable APK; the CI workflow can compile with a dummy `google-services.json`, which proves buildability but not live cloud connectivity.
2. Add an explicit OCR extraction path to supplement accessibility and vision, then fuse OCR output into DeviceContext.
3. Expand the keyboard toward the canonical compact top bar + full feature drawer and improve key ergonomics.
4. Add richer task detail/timeline UI and remote-job controls/status evidence in the Android app.
5. Verify real Gemini Live audio, interruption, screen streaming, and GitHub function calls on an Android device.
6. Verify on-device Gemini support on the owner's actual device and keep Local Only from falling back to cloud.
7. Keep documentation synchronized with only what is actually verified.

## Verification rule
Do not mark a feature as verified merely because source code exists. Mark it verified only when the relevant test, GitHub Actions evidence, tool result, or real-device behavior proves it.
