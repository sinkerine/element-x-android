#!/usr/bin/env bash
# Copyright 2026 Element Creations Ltd.
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
set -euo pipefail
cd "$(dirname "$0")/../../.."
name=element-x-interactive-ui-synapse
if ! docker container inspect "$name" >/dev/null 2>&1; then echo 'Dedicated Synapse is already stopped'; exit 0; fi
owner=$(docker container inspect "$name" --format '{{index .Config.Labels "element-x.interactive-ui.workspace"}}')
[[ $owner == "$PWD" ]] || { echo 'Container belongs to a different workspace' >&2; exit 1; }
docker stop "$name" >/dev/null
docker rm "$name" >/dev/null
echo 'Stopped dedicated Synapse; data retained under build/e2e/interactive-ui/synapse'
