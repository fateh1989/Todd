# Todd — Local prompt library (prompts.chat)

Status: implementation on feature branch, NOT verified on a phone.

Source: https://github.com/f/prompts.chat
Pin: `bd4ed9313fdb1204e8c256d709d13694f72581de`
Source file: `prompts.csv`, Git blob SHA-1 `f869b9947ed309d180f737bbd724ff0496777f62`.
Imported prompt text: CC0, see upstream license. Android glue code remains Todd code.

## What this changes
- A one-time, reproducible Python import selects up to 200 relevant original prompts.
- Generated, UTF-8 TSV data is stored in `app/src/main/assets/todd_prompts.tsv` and bundled into the APK.
- Matching and selection happen on the phone; the app does **not** call prompts.chat.
- Only one task-relevant excerpt is inserted as **untrusted reference context** into the existing Todd text agent.
- Project/owner rules, local/cloud choice, approvals, GitHub permissions, memory and keystore are not replaced.
- No new login, API key, third-party SDK, or runtime data collection.
- No exact-quality claims: heuristic filtering is not a manual audit of 200 prompt texts.

## Generated asset
`scripts/vendor_prompts.py` fetches only a pinned official source, checks its Git blob hash,
parses CSV correctly including multiline quoted entries, filters/uniquifies and writes
the TSV and a JSON manifest. Import is intended to be run once by a GitHub Action on
the feature branch and committed to the repository; future local builds do not need internet.
If the asset is absent, Todd simply uses existing prompts and does not crash.

## What to test
1. Verify `todd_prompts_manifest.json` says 150–200 and matches TSV line count.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Confirm the CI Android APK is signed by Todd's existing stable certificate.
4. On an installed Android APK, compare normal requests and Android/translation/research/coding requests, including offline mode.
5. Confirm permission prompts and tool calls still require user approval; no false verification.
6. For debugging, note there is no new UI to show which prompt is selected. Integration is transparent.

Rollback: revert only the prompt-library commits; the existing earlier verified `main` head was
`0bd0af5246248bec50a2837882c37e0adc6f77dc`.
