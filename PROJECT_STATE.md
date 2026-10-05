# Todd — Project State

Last refresh: 2026-10-05

## Goal
Complete Todd as a native Android-first persistent personal AI agent with text/voice, screen context, durable memory, live research, GitHub tools, autonomous coding, scheduling, and reconnectable remote verification.

## Repository
- Repository: fateh1989/Todd
- Default branch: main
- Implementation head before this state update: 7e80ec077804ea589ba2b33a394b21a5809248cc
- Canonical product definition: TODD_MASTER_SPEC.md
- Google AI Studio continuation: GOOGLE_AI_STUDIO_BUILD_PROMPT.md

## Latest completed CI evidence
- Verified code head: c06dc40410e56b514344e8f487237366d2832f96
- Todd Android CI run 90, ID 37267217838
- Unit tests: success
- APK assembly: success
- APK existence: success
- APK signature verification: success
- Artifact upload: success

Newer changes after run 90 must not be described as verified until their own workflow is green.

## Physical-device evidence
Verified on the owner's Android device:
- Build 81 installed and launched.
- Direct Gemini text returned a real conversational response.
- Todd Accessibility service was enabled.
- Floating Todd overlay had previously appeared and opened.

Not yet physically verified from newer code:
- keyboard IME resize/send-button fix;
- manual Gemini model selector;
- direct Interactions tool calls and live Google Search;
- repository list/read tools through chat;
- learned-memory review/delete UI;
- live permission-status UI.

## Implemented in current main
- Kotlin / Jetpack Compose Android app with Room persistence.
- Persistent projects, tasks, scheduled work, memories, failures and rules.
- Explicit durable learning through MemoryLearningEngine.
- Floating Todd overlay and Todd IME keyboard.
- Accessibility, MediaProjection, OCR and notification context fusion.
- AI modes: Local Only / Auto / Cloud Preferred.
- Encrypted Gemini API credential storage on Android.
- Manual persisted Gemini model choice:
  - Gemini 3.5 Flash-Lite for frequent/light work.
  - Gemini 3.8 Flash for coding/difficult work.
- The owner chooses the cloud model; Todd must not silently auto-switch models.
- Direct Gemini text path independent of Firebase.
- Direct Gemini Interactions client with Google Search and custom Todd tools.
- Text tools for device context, repository/workflow state, repository list/read, autonomous coding, scheduling, task status and remote-job control.
- Existing Gemini Live voice path.
- GitHub repository tooling with encrypted local authorization storage.
- Persistent autonomous coding loop with durable checkpoints.
- GitHub Actions remote Android verification with reconnect/cancel/evidence.
- One-time scheduled model results are COMPLETED, not falsely VERIFIED without external evidence.
- Chat composer uses IME padding and MainActivity uses adjustResize.

## Current limitations
- A permanent Todd signing identity has now been generated outside the repository and its public certificate fingerprint is pinned in code/workflow. The private keystore is NOT committed. GitHub Actions secrets still need to be configured before the first stable release APK can be produced and verified.
- Expected permanent signer SHA-256: `2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92`.
- Gemini Live voice is not physically verified and still needs real runtime configuration.
- The owner's physical device reported the on-device model unavailable; local AI is not verified.
- Direct search/tool behavior is not physically verified yet.
- Autonomous repository coding requires owner authorization on the device.

## Verification rule
Always distinguish proposed, implemented, CI/build verified, and physical-device verified. Never claim success without matching evidence.
