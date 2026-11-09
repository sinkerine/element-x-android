#!/usr/bin/env bash
# Copyright 2026 Element Creations Ltd.
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
set -euo pipefail
cd "$(dirname "$0")/.."

# All worktrees on this host share the lock. Direct gradlew invocations bypass it.
lock_dir="${XDG_CACHE_HOME:-$HOME/.cache}/element-x-android"
mkdir -p "$lock_dir"
exec 9>"$lock_dir/gradle.lock"
echo 'Waiting for the local Element X build slot...'
flock 9

# R8 needs the larger heap for release builds. Only explicitly debug tasks use 4g.
heap=8g
if [[ $# -gt 0 ]]; then
    debug_only=true
    for arg in "$@"; do
        case "$arg" in
            -*) ;;
            *Debug*) ;;
            *) debug_only=false ;;
        esac
    done
    if [[ $debug_only == true ]]; then heap=4g; fi
fi

# A single-use daemon exits after the build, before the E2E emulator starts.
exec ./gradlew "$@" --max-workers=2 --no-parallel --no-daemon \
    "-Dorg.gradle.jvmargs=-Xmx$heap -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
    -Pkotlin.daemon.jvmargs=-Xmx2g
