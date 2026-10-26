---
name: element-x-android-e2e
description: Test Element X Android features end to end on an Android emulator. Use for feature verification, reproducing UI bugs, checking navigation and Matrix sync, or adding and running Maestro regression flows in this repository. Covers building and installing the debug APK, Orca device control, test-account setup, assertions, and failure diagnosis.
---

# Element X Android emulator feature testing

Run commands from the repository root (three directories above this skill directory). Read root `AGENTS.md` first. This skill applies to this repository's actual Android app and Matrix integration.

## Choose the scenario

1. Read the affected `features/<feature>/impl` screen, presenter, navigation node, and any related `.maestro/tests` flows.
2. Turn the requested behavior into a short sequence with preconditions, actions, and observable expected results. Include the main success path and relevant failure, cancellation, or persistence behavior. Keep coverage proportional to the change.
3. Use interactive Orca control for exploration and reproductions. Use Maestro when an existing flow covers the scenario or a repeatable regression flow is warranted. A successful launch alone does not verify a feature.
4. For network features, use dedicated Matrix test accounts and rooms. Establish the homeserver, account, room, verification state, and any second participant before testing. The app cannot create an account; provision one separately or use an existing test account.

Use account and device choices already supplied in the session. If essential credentials or fixtures are unavailable, continue independent build and device checks, then ask only for the missing information. Never claim an authenticated scenario passed when it was blocked at login.

## Check the environment

Read these sources when selecting tools and variants:

- `plugins/src/main/kotlin/Versions.kt`: JDK, compile SDK, build tools, minimum runtime SDK.
- `app/build.gradle.kts` and `plugins/src/main/kotlin/config/BuildTimeConfig.kt`: package, build variants, APK splits.
- `flake.nix`: optional Nix development environment.
- `.github/workflows/maestro-local.yml`: current CI emulator and build configuration.
- `.maestro/README.md`, `.maestro/allTests.yaml`, and `.maestro/scripts/checkEnv.js`: suite entry point and variables.

At the time of writing, builds require JDK 21, Android compile SDK 37, and build tools 37.0.0. CI tests on API 33, Google APIs, x86_64, Pixel 7 Pro. The compile SDK does not require the emulator to run that same API level; respect the variant's minimum SDK and any feature-specific API requirement.

```sh
java -version
adb version
```

On Nix, enter `nix develop` (or use the existing direnv shell) for build dependencies. The checked-in flake provides the JDK, build tools, platform tools, and compile platform; it does **not** provide Maestro, the emulator binary, or system images. Check for these separately. An already booted emulator may be available through Orca even when `emulator` is absent from this shell's PATH.

If no AVD exists, create a dedicated test AVD using Android Studio Device Manager or the host SDK's tools. Prefer the CI profile on x86_64 hosts and a host-compatible image on ARM hosts. Verify available SDK tools and their help before constructing commands; do not try to write system images into a read-only Nix SDK. Report a missing emulator/image as a setup blocker with the exact missing dependency.

## Select and inspect the emulator through Orca

When operating inside Orca, read the installed `orca-emulator-android` skill and fetch its full version-matched guide before device commands. Resolve the executable once:

1. Use `ORCA_CLI_COMMAND` when set.
2. Otherwise use `orca-dev` when `ORCA_DEV_REPO_ROOT` is exposed.
3. Otherwise prefer `orca-ide-cli` when available.
4. On Linux outside an Orca-managed terminal, use `orca-ide`; bare `orca` may be the GNOME screen reader.
5. Otherwise use `orca`.

`ORCA` below means that resolved executable, substituted directly. If it fails, report the exact error; do not silently switch binaries. Follow the installed guide if command syntax changes.

```text
ORCA skills get orca-emulator-android
ORCA status --json
ORCA emulator devices --json
ORCA emulator attach <avd-name-or-serial> --json
ORCA emulator exec --device <serial> --command "getprop sys.boot_completed" --json
ORCA emulator exec --device <serial> --command "getprop ro.product.cpu.abi" --json
ORCA emulator exec --device <serial> --command "getprop ro.build.version.sdk" --json
```

Start Orca with `ORCA open --json` if needed. Select an Android **emulator** from the actual device listing. Reuse a dedicated test emulator when available; do not hard-code a serial or select an unrelated physical phone. Record its serial, API, and ABI. Wait for boot completion (`1`) using bounded polling before installing. Pin subsequent commands with `--device <serial>`, especially with multiple devices or agents.

Outside Orca, use the host Android SDK and explicitly target every adb operation with `adb -s <serial>`. Use `adb devices -l` to resolve the serial. Do not assume an unqualified adb command reaches the chosen emulator.

## Build, install, and launch

Use the standard FOSS Google Play debug variant unless the requested behavior needs another variant:

```sh
./gradlew :app:assembleGplayDebug
```

Expected artifacts:

- x86_64: `app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk`
- ARM64: `app/build/outputs/apk/gplay/debug/app-gplay-arm64-v8a-debug.apk`
- Universal fallback: `app/build/outputs/apk/gplay/debug/app-gplay-universal-debug.apk`
- Default debug package: `io.element.android.x.debug`

Verify the selected APK exists and the build succeeded before installation. Rebuild and reinstall after app source changes so the test exercises the current code.

```text
ORCA emulator install app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk --reinstall --device <serial> --json
ORCA emulator launch io.element.android.x.debug --device <serial> --json
ORCA emulator ax --device <serial> --json
```

Choose the ABI-specific path matching the device. Reinstall preserves app data. If installation reports a signature mismatch or downgrade, report the cause and use a disposable test device/package before considering data removal. Do not uninstall or clear an existing account merely to get a clean start. Debug testing uses the repo's debug signing configuration; release distribution signing is documented separately in `AGENTS.md`.

## Exercise and assert the feature

Inspect the accessibility tree before input and after each meaningful transition. Use visible text, content descriptions, and exposed resource IDs to identify controls; derive coordinates from the current tree or live emulator view. Orca tap coordinates are normalized 0..1, not pixels. Reinspect after layout changes, keyboard changes, scrolling, or rotation.

```text
ORCA emulator tap <normalized-x> <normalized-y> --device <serial> --json
ORCA emulator type "ASCII test text" --device <serial> --json
ORCA emulator button back --device <serial> --json
ORCA emulator ax --device <serial> --json
```

Consult the fetched guide for scrolling, rotation, permissions, and other commands. Orca typing supports US-ASCII without newlines; choose another supported input method for Unicode scenarios. Grant/revoke permissions only when required by the scenario, and record changes so they can be restored.

Assert the resulting UI and behavior explicitly. For Matrix features, wait for sync using a bounded deadline and a concrete visible condition. Distinguish local optimistic UI from server delivery: for sending, verify the pending/error state resolves and, where relevant, confirm receipt on a second test session or after reopening the room. Record feature-flag settings and restore changes made for the test.

Use existing synthetic fixture data. Sending messages, invitations, calls, and membership changes must target dedicated test fixtures within the requested scenario. Do not use personal rooms or initiate communications with unrelated users.

## Run or extend Maestro

Check `maestro --version` and `maestro test --help` before use; install a missing CLI from its official setup instructions using the host's supported method. Keep Maestro on the same adb device as Orca by specifying the serial explicitly.

Existing flows are stateful subflows. For example, a timeline flow may require an open room, and login expects onboarding. Inspect the chosen flow and its callers to establish preconditions. Use a wrapper flow when setup and assertions are needed; do not run an arbitrary subflow and interpret a precondition failure as a product bug.

The full suite requires these variables in the command environment:

```text
MAESTRO_APP_ID=io.element.android.x.debug
MAESTRO_USERNAME
MAESTRO_PASSWORD
MAESTRO_RECOVERY_KEY
MAESTRO_ROOM_NAME
MAESTRO_INVITEE1_MXID
MAESTRO_INVITEE2_MXID
```

Supply secrets through the existing secure environment or a temporary, owner-only file outside the repo; never print them or put literal secrets in a command, flow, report, or commit. Confirm presence without displaying values. Use the installed Maestro version's supported environment forwarding. Do not reuse CI's shared account without explicit availability for this run.

The full suite runs `.maestro/tests/init.yaml`, which **clears app state**, then signs in, verifies the session, exercises settings and rooms, sends messages/invites, and logs out. Run it only on a disposable test installation with dedicated fixtures; preserve an existing session for focused testing.

```sh
mkdir -p build/e2e/maestro
maestro --device <serial> test --test-output-dir build/e2e/maestro .maestro/allTests.yaml
```

For a focused test, replace the entry point with the appropriate flow or wrapper after satisfying its preconditions and required variables. Stop any `maestro studio` instance before running tests if it holds the device.

When adding coverage, follow `.maestro/tests` conventions: `appId: ${MAESTRO_APP_ID}`, stable selectors from the actual hierarchy, bounded `extendedWaitUntil`, and explicit assertions of the requested outcome. Compose test tags are usable only if exposed in the accessibility hierarchy; verify IDs on-device. Avoid fixed sleeps, guessed selectors, and retries around non-idempotent actions such as sending. Flows should normally leave the app on the screen where they started. Document fixtures and cleanup for flows that intentionally change state. Do not wire a destructive flow into the full suite by accident.

## Diagnose, clean up, and report

On failure, retain the failing step, expected and actual state, relevant accessibility evidence, and Maestro output under ignored `build/e2e/`. Retrieve only bounded logcat output when needed:

```text
ORCA emulator logcat --lines 200 --device <serial> --json
```

Logs, UI dumps, and Maestro artifacts may contain credentials or user content. Keep raw artifacts local and inspect/redact before sharing. Never enable private-data logging or add logs for secrets or message bodies. Follow the repository rule against locally recording screenshot-test baselines; CI records those. Existing Maestro artifact behavior is separate from Paparazzi baseline generation.

Classify the result as passed, failed, or blocked. Distinguish product failures from missing credentials, homeserver outages, build dependency failures, and automation selector failures. Do not report a pass based on clicks or process exit status alone when the required behavior was not observed.

Restore temporary permissions, rotation, feature flags, and synthetic fixtures where possible. End any recording/logging processes you started. Run `ORCA emulator kill --device <serial> --json` to release your helper when finished; leave a reused emulator booted unless shutdown was requested.

Report the feature and scenario, commit/workspace state, APK variant and build result, emulator API/ABI/serial, assertions and outcomes, artifact paths, and any remaining blockers. State clearly which scenarios were actually executed. Never include passwords, recovery keys, tokens, or private user content.
