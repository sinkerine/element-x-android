#!/usr/bin/env bash
# Copyright 2026 Element Creations Ltd.
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
set -euo pipefail
cd "$(dirname "$0")/../../.."
[[ $(git branch --show-current) == interactive-ui-android ]] || { echo 'Wrong branch' >&2; exit 1; }
sdk="$HOME/.local/share/element-x-e2e/android-sdk"
export ANDROID_AVD_HOME="$HOME/.local/share/element-x-e2e/avds"
export ANDROID_HOME="$sdk"
export ANDROID_SDK_ROOT="$sdk"
name=interactive_ui_a1
serial=emulator-5584
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo 'KVM unavailable' >&2; exit 1; }
[[ -d "$sdk/system-images/android-33/google_apis/x86_64" ]] || { echo 'Missing API 33 google_apis x86_64 image' >&2; exit 1; }
[[ -x $adb && -x $emulator && -x $avdmanager ]] || { echo 'Missing host Android tools' >&2; exit 1; }
mkdir -p "$ANDROID_AVD_HOME" build/e2e/interactive-ui
if "$adb" devices | awk '{print $1}' | grep -qx "$serial"; then
    [[ $("$adb" -s "$serial" emu avd name | head -1 | tr -d '\r') == "$name" ]] || { echo 'Emulator port belongs to another AVD' >&2; exit 1; }
    echo 'Dedicated emulator already running; do not claim it was started by this invocation'
    exit 0
fi
for port in 5584 5585; do
    if ss -H -ltn "sport = :$port" | read -r _; then echo "Port $port is occupied" >&2; exit 1; fi
done
if [[ ! -f $ANDROID_AVD_HOME/$name.ini ]]; then
    # Nix's avdmanager wrapper pins toolsdir to its read-only SDK. Run the public CLI
    # class with this writable SDK explicitly, so it discovers the installed image.
    java_bin="${JAVA_HOME:?Set JAVA_HOME to JDK 21}/bin/java"
    printf 'no\n' | "$java_bin" -Dcom.android.sdkmanager.toolsdir="$sdk/cmdline-tools/latest" \
        -Dcom.android.sdklib.toolsdir="$sdk/cmdline-tools/latest" \
        -cp "$sdk/cmdline-tools/latest/lib/avdmanager-classpath.jar" com.android.sdklib.tool.AvdManagerCli \
        create avd --name "$name" --package 'system-images;android-33;google_apis;x86_64' --device pixel_7_pro >/dev/null
fi
# On NixOS invoke this script in a host runtime wrapper, e.g. steam-run bash .../avd-up.sh.
"$emulator" -version >/dev/null
nohup "$emulator" -avd "$name" -port 5584 -no-window -no-audio -no-boot-anim -no-snapshot-save \
    -gpu swiftshader_indirect -camera-back none >build/e2e/interactive-ui/emulator.log 2>&1 </dev/null &
emulator_pid=$!
printf '%s\n' "$emulator_pid" >build/e2e/interactive-ui/emulator.pid
for ((i=0; i<120; i++)); do
    if [[ $("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        [[ $("$adb" -s "$serial" emu avd name | head -1 | tr -d '\r') == "$name" ]] || exit 1
        "$adb" -s "$serial" shell getprop ro.build.version.sdk
        "$adb" -s "$serial" shell getprop ro.product.cpu.abi
        echo "Dedicated emulator ready: $serial (started pid $emulator_pid)"
        exit 0
    fi
    kill -0 "$emulator_pid" 2>/dev/null || { echo 'Emulator exited; inspect local emulator.log' >&2; exit 1; }
    sleep 1
done
echo 'Dedicated emulator boot timed out' >&2
exit 1
