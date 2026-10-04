# Todd — Project State

Last verified update: 2026-10-04

## Current goal
Build Todd as a native Android-first persistent personal AI agent that can converse by text/voice, understand the current phone context broadly, remember projects, use GitHub tools, research with live grounding, and hand long-running build/test work to a reconnectable remote execution layer.

## Repository
- Repository: `fateh1989/Todd`
- Default branch: `main`
- Current verified head: `b8e75da9f506828d1b89eae0609c76d73d832448`

## Verified Android build evidence
- GitHub Actions workflow: `Todd Android CI`
- Run: `37245416089`
- Head SHA: `b8e75da9f506828d1b89eae0609c76d73d832448`
- Unit tests: success
- Debug APK assembly: success
- APK existence check: success
- Artifact upload: success
- Artifact: `app-debug`
- Artifact ID: `11318987053`
- Artifact size: 41,620,939 bytes
- Artifact SHA-256 digest reported by GitHub: `dadbd876cbbd6df3d55284d6ca545d603aff99d3a5b8c8fc9d97aa45e3174a85`

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
- AI routing modes: Local Only / Auto / Cloud Preferred.
- Firebase AI Logic cloud Gemini provider using `gemini-3.8-flash`.
- Gemini on-device provider using the dedicated `firebase-ai-ondevice` runtime and strict `ONLY_ON_DEVICE` inference mode.
- Settings controls that report real Firebase runtime configuration status and let the owner check/prepare the on-device model.
- Real Gemini Live session integration using `gemini-3.1-flash-live-preview`, live audio APIs, transcription, interruption support, function calling, and live visual frame streaming while screen capture is active.
- GitHub REST read/write tooling with Android Keystore-backed encrypted credential storage.
- GitHub repository inspection, file reading, workflow reading, and real commit creation tools.
- Persistent GitHub Actions-backed remote execution with reconnect, cancellation, exact start-commit verification, Android tests/builds, and artifact evidence.
- Remote task status synchronization into Room plus Refresh/Cancel controls in the Activity screen.
- Accessibility semantic screen extraction.
- Continuous MediaProjection screen capture.
- ML Kit local OCR extraction fused into DeviceContext.
- Fused device context using accessibility, visual capture, OCR, notifications, keyboard context, and app/window metadata.
- Notification listener context.
- Todd IME keyboard with Arabic/English typing, Enter, centered space, backspace, compact top bar, feature drawer, clipboard, project notes, and AI writing/research actions.
- Floating Todd button with drag/snap, hide/power targets, and compact assistant controls.
- Live Google Search grounding for cloud research responses, with grounded source metadata preserved in memory/chat.

## Toolchain verified by current green build
- Kotlin: 2.3.21
- Android Gradle Plugin: 8.10.0
- KSP: 2.3.9
- Room: 2.8.5
- Gradle wrapper: 8.11.1
- Firebase AI on-device runtime: 16.0.0-beta05

## Important technical distinction
The repository has verified compile/test/APK evidence and a verified remote build/test worker. This does not by itself prove every device-dependent capability works on the owner's physical Android device.

## Screen-awareness direction
Todd should read and understand as much of the phone screen/context as technically available when the feature is enabled. It combines AccessibilityService, MediaProjection, visual analysis, local OCR, InputConnection, notifications, clipboard context when used, and app/window metadata. If one source has no data, the others remain available. Todd does not add an internal app/content blocklist; actual Android/OS API boundaries remain technical limits.

## Remaining verification / implementation gaps
1. Install the current APK on the owner's Android device and perform device-level verification.
2. Supply a real Firebase `google-services.json` at build time and verify a real cloud Gemini request; CI currently uses a build-only placeholder when no secret is supplied.
3. Verify Gemini Live microphone/audio, interruption, screen streaming, and GitHub function calls on the physical device.
4. Verify on-device Gemini support, model preparation/download, local text response, and strict no-cloud behavior on the physical device.
5. Verify Android permission flows, Todd keyboard ergonomics, clipboard behavior, overlay drag/hide/power behavior, notification access, accessibility context, MediaProjection, and OCR on the physical device.
6. Extend the remote layer from verified build/test execution into a fully autonomous long-running coding loop when a real model/provider is available to drive iterative edits.
7. Keep documentation synchronized only with evidence actually obtained.

## Verification rule
Do not mark a feature as verified merely because source code exists. Mark it verified only when the relevant test, GitHub Actions evidence, tool result, or real-device behavior proves it.
