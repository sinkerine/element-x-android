---
name: element-x-android-apk-release
description: Build a fresh Element X Android release APK from the current HEAD, sign it with the existing local release key, and install it in place on an ADB device. Use for requests to install or update a release APK, including repeating a previous build and install.
---

# Build and install a release APK

Run from the current worktree's repository root. Read root `AGENTS.md` first for signing and installation requirements.

Invoke with `/skill:element-x-android-apk-release` to build and install the current HEAD. Optionally supply the ADB serial, for example `/skill:element-x-android-apk-release install on <serial>`. Default to the Gplay release and the session's selected device, or the only connected device in the `device` state. If several devices are connected and none is selected, ask which one to use.

Complete the build, signing, installation, and verification when this skill is invoked. A request to create or edit this skill only authorizes changing the skill; it does not itself request an APK build or device installation.

## Build the current HEAD

An instruction to install a new release APK, or to do it again, means **build a fresh release from the current HEAD, sign it, and install it**. Do not reuse an existing APK merely because its modification time is recent or its version matches. Re-read HEAD on every request; it may have advanced since the last installation.

1. Inspect `git status --short` and record `git rev-parse HEAD` and the commit title. Build the committed source at that HEAD. Preserve local changes; if uncommitted changes affect build inputs, build in a separate temporary detached Git worktree at the recorded commit. Prepare its development environment and dependencies using the repository instructions. Include uncommitted source changes only if the user explicitly requests them, and disclose that provenance.
2. Use the repository development environment. If `adb` or Java is missing from PATH, use `nix develop --command ...`; the checked-in flake supplies the Android SDK and JDK. In that shell, build tools are available under `$ANDROID_HOME/build-tools/<version>/` even if `apksigner` and `aapt` are not on PATH. Select an installed version.
3. Inspect `adb devices -l`, select the intended connected device, and pin all device commands with `adb -s <serial>`. Reuse the user's device choice from the session; ask only if the target is ambiguous.
4. Check the device ABI using `adb -s <serial> shell getprop ro.product.cpu.abi`. Inspect the installed package and its version, `firstInstallTime`, and `lastUpdateTime` with `dumpsys package`. The primary release package is `io.element.android.x`; confirm against APK metadata.
5. Build with `./tools/gradle-local.sh :app:assembleGplayRelease --console=plain` inside the development environment unless the user requested another flavor. Gradle incremental task reuse is fine; a clean build is not required. Require a successful build before using its outputs.
6. Select the matching ABI APK from this successful build, for example `app/build/outputs/apk/gplay/release/app-gplay-arm64-v8a-release.apk` for an ARM64 phone. Use the build's output metadata to confirm the variant and ABI. Confirm the original worktree's HEAD has not changed during the build; if it has, build again for the new HEAD. Recheck it immediately before installation.

The local build wrapper serializes builds across worktrees on this host, limits Gradle to two workers with parallel task execution disabled, and uses a single-use daemon. It retains an 8 GiB Gradle heap for release R8 and caps the Kotlin daemon heap at 2 GiB. Direct `gradlew` invocations bypass the lock. Schedule release builds separately from emulator tests; preserve emulators being used by other sessions.

For a long build, redirect output to an ignored file under `build/adb-install/`, run it in the background if necessary, and record its exit code. Check progress with short waits and keep the user updated. A tool timeout is not evidence that the build succeeded; verify completion before signing.

## Sign with the existing local key

The Gradle release configuration currently uses the debug key. Treat its output as staging and re-sign it before installation. Follow `AGENTS.md` if key locations or build configuration change.

- Keystore: `/home/sinkerine/.local/share/element-x-local-signing-0550e5GE/element-x-local.jks`
- Alias: `element-x-local`
- Password file: `/home/sinkerine/.local/share/element-x-local-signing-0550e5GE/password`
- Keep all secrets local; never print password contents or put them in command arguments, logs, commits, or uploads.

Use `apksigner sign --ks ... --ks-key-alias element-x-local --ks-pass file:<password-file> --out <signed-apk> <staging-apk>`. With this keystore, omit `--key-pass`: it uses the keystore password. Passing the same password file for both options can exhaust the file reader and fail with an end-of-file error.

Save the signed artifact under ignored `build/adb-install/`, including the commit hash and ABI in its name, for example `element-x-<commit>-arm64-release-signed.apk`. Verify it with `apksigner verify --print-certs` and inspect package/version metadata with `aapt dump badging`. Record its SHA-256 checksum with `sha256sum` and keep a small provenance file beside it containing the full source commit, variant, ABI, package/version, artifact checksum, and signer digest. Keep passwords and other secrets out of this file. If using a temporary worktree, retain the signed artifact and provenance in the original worktree before removing the temporary worktree.

Confirm its signer SHA-256 digest matches the installed app. If needed, resolve the installed base APK via `adb -s <serial> shell pm path io.element.android.x`, pull it into `build/adb-install/`, and inspect it with `apksigner verify --print-certs`. The established local certificate SHA-256 digest is:

```text
35275590b3299773b1749f2348f96ed558b11f98906f6b4a2cfec799c9f02c4a
```

A mismatch requires resolving the signing setup; do not uninstall the app, regenerate the key, or wipe data to work around it. When distributing both primary and second APKs, sign both with the same canonical key and verify their signer digests match.

## Install and verify the update

1. Confirm the chosen device remains steadily connected with `adb devices -l`.
2. Install once using `adb -s <serial> install -r --no-incremental <signed-apk>`. Always include `--no-incremental`: incremental installs can lose the package and its data if streaming is interrupted. Do not retry a failed installation in a loop.
3. Require the install result to report `Success`.
4. Inspect `adb -s <serial> shell dumpsys package io.element.android.x` again. Confirm the expected version and that the original `firstInstallTime` is preserved and older than `lastUpdateTime`. The version number may remain unchanged between commits; use build provenance and update timestamps as well.
5. Report the commit hash/title, successful release build, checks actually run, signing verification, APK SHA-256 checksum, device, installation result, preserved installation timestamp, and a link to the signed artifact. Do not claim lint or unit tests passed unless their results were checked. Do not claim functional testing or authenticated session verification based solely on an installation.

Preserve app data and existing sessions. Never uninstall, clear data, or use personal rooms to prepare an update. Do not record screenshot-test baselines locally.
