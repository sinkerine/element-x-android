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

# Source-only reproduction uses direct Gradle descendants while this launcher retains the shared guard.
if [[ ${1:-} == --bootstrap-foundation ]]; then
    shift
    exec python3 tools/foundation/bootstrap "$@"
fi

# R8 needs the larger heap for release builds. Only explicitly debug tasks use 3g.
heap=8g
if [[ $# -gt 0 ]]; then
    debug_only=true
    has_debug_task=false
    skip_test_filter=false
    for arg in "$@"; do
        if [[ $skip_test_filter == true ]]; then
            skip_test_filter=false
            continue
        fi
        case "$arg" in
            --tests) skip_test_filter=true ;;
            -*) ;;
            *Debug*) has_debug_task=true ;;
            *) debug_only=false ;;
        esac
    done
    if [[ $debug_only == true && $has_debug_task == true ]]; then heap=3g; fi
fi

# A single-use daemon exits after the build, before the E2E emulator starts.
exec ./gradlew "$@" --max-workers=2 --no-parallel --no-daemon \
    "-Dorg.gradle.jvmargs=-Xmx$heap -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
    -Pkotlin.daemon.jvmargs=-Xmx1g
