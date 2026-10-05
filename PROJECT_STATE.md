# Todd — Project State

Last refresh: 2026-10-05

## Goal
Complete Todd as a native Android-first persistent personal AI agent with text/voice, screen context, durable memory, live research, GitHub tools, autonomous coding, scheduling, and reconnectable remote verification.

## Repository
- Repository: fateh1989/Todd
- Default branch: main
- Implementation head: b52b056f5cba47e2c46df6dcec5eb77ccf08deee
- Commit message: fix(release): use one bundle in stable release
- Canonical product definition: TODD_MASTER_SPEC.md
- Google AI Studio continuation: GOOGLE_AI_STUDIO_BUILD_PROMPT.md

## Latest completed CI evidence
- GitHub Actions secret configured: `TODD_SIGNING_BUNDLE` (single bundle containing all signing material).
- Todd Android CI run 109 attempt 2 succeeded.
- Build log verified: `Stable Todd APK signing is configured.`
- APK certificate verified: `Signer #1 certificate SHA-256 digest: 2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92`.
- Permanent signing identity confirmed: `2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92`.
- Unit tests: success
- APK assembly: success
- APK existence: success
- APK signature verification: success
- Artifact upload: success

## Physical-device evidence
Verified on the owner's Android device:
- Build 81 installed and launched.
- Direct Gemini text returned a real conversational response.
- Todd Accessibility service was enabled.
- Floating Todd overlay had previously appeared and opened.

CI verified, physical-device verification pending:
- build and release metadata diagnostics card (Version name/code, Git commit SHA, Actions run number, build timestamp, signing status, certificate SHA-256);
- keyboard IME typing, actions (rewrite, correct, translate, summarize, explain, continue, reply, research), and direct Todd agent routing;
- manual Gemini model selector (`gemini-3.5-flash-lite` vs `gemini-3.8-flash`);
- direct Interactions tool calls and live Google Search grounding;
- Gemini Live direct WebSocket (`gemini-3.8-live`) with audio I/O and tool calling;
- floating overlay drag target bar (Hide vs Power Off with 1.2s hold) and state-aware visual button badge;
- repository list/read tools through chat;
- active rules and tool permissions in Room memory context;
- permission status UI (hides action button when permission is already granted).

## Implemented in current main
- Kotlin / Jetpack Compose Android app with Room persistence.
- Single secret `TODD_SIGNING_BUNDLE` verified in `.github/workflows/android.yml` and `.github/workflows/stable-release.yml`.
- Pinned permanent signing SHA-256: `2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92`.
- Persistent projects, tasks, scheduled work, memories, failures and rules.
- Explicit durable learning through MemoryLearningEngine.
- Floating Todd overlay with drag target bar (Hide / Power Off hold) and state-aware visual indicators.
- Todd IME keyboard with complete typing layout and direct full-agent actions.
- Accessibility, MediaProjection, OCR and notification context fusion.
- AI modes: Local Only / Auto / Cloud Preferred.
- Encrypted Gemini API credential storage on Android Keystore.
- Manual persisted Gemini model choice:
  - Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`) for frequent/light work.
  - Gemini 3.8 Flash (`gemini-3.8-flash`) for coding/difficult work.
  - Gemini 3.8 Live (`gemini-3.8-live`) for live voice sessions.
- The owner chooses the cloud model; Todd never silently auto-switches models.
- Direct Gemini text path independent of Firebase.
- Direct Gemini Interactions client with Google Search grounding and custom Todd tools.
- Text tools for device context, repository/workflow state, repository list/read, autonomous coding, scheduling, task status and remote-job control.
- Direct Gemini Live voice WebSocket client with PCM16 audio, tools, and interruption handling.
- GitHub repository tooling with encrypted local authorization storage.
- Persistent autonomous coding loop with durable checkpoints.
- GitHub Actions remote Android verification with reconnect/cancel/evidence.
- One-time scheduled model results are COMPLETED, not falsely VERIFIED without external evidence.
- Permissions UI dynamically updates and hides activation buttons once active.
- Read-only Build & Release Metadata card in Settings/Diagnostics showing version name/code, Git commit SHA, GitHub Actions run number, build timestamp, signing status, and certificate SHA-256 without exposing keys or secrets.
- Chat composer uses IME padding and MainActivity uses adjustResize.

## Latest build fix pending verification
- Todd Android CI run 110 failed because the Gradle Kotlin DSL could not resolve the fully-qualified `java.time` expression used for build metadata.
- The build metadata code now imports `java.time.Instant` and uses `Instant.now().toString()`.
- The light Gemini model has been moved from deprecated `gemini-3.1-flash-lite` to current `gemini-3.5-flash-lite`.
- These newest changes must receive a green GitHub Actions run before they are described as CI verified.

## Current limitations
- The owner's physical device reported the on-device model unavailable; local AI is optional and does not block Todd.
- Direct search, tool behavior, and Gemini Live voice require physical device verification after APK download.
- Autonomous repository coding requires owner authorization on the device.

## Verification rule
Always distinguish proposed, implemented, CI/build verified, and physical-device verified. Never claim success without matching evidence.
