#!/usr/bin/env bash
# Copyright 2026 Element Creations Ltd.
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
set -euo pipefail
cd "$(dirname "$0")/../../.."
[[ $(git branch --show-current) == interactive-ui-android ]] || { echo 'Wrong branch' >&2; exit 1; }
name=element-x-interactive-ui-synapse
sandbox="$PWD/build/e2e/interactive-ui/synapse"
mkdir -p "$sandbox"
chmod 700 "$sandbox"
umask 077
if docker container inspect "$name" >/dev/null 2>&1; then
    echo 'Dedicated container already exists; inspect ownership and stop it explicitly before rerunning.' >&2
    exit 1
fi
if ss -H -ltn 'sport = :18028' | read -r _; then echo 'Port 18028 is occupied' >&2; exit 1; fi
image=$(docker image inspect matrixdotorg/synapse:latest --format '{{index .RepoDigests 0}}')
[[ $image == matrixdotorg/synapse@sha256:* ]] || { echo 'No local Synapse RepoDigest' >&2; exit 1; }
printf '%s\n' "$image" > "$sandbox/image-digest.txt"
if [[ ! -f $sandbox/homeserver.yaml ]]; then
    docker run --rm --user "$(id -u):$(id -g)" -v "$sandbox:/data" \
        -e SYNAPSE_SERVER_NAME=interactive-ui.test -e SYNAPSE_REPORT_STATS=no "$image" generate >/dev/null
    # Generated signing/registration secrets never enter stdout or version control.
    python3 - "$sandbox/homeserver.yaml" <<'PY'
import pathlib, re, secrets, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
s = re.sub(r'^registration_shared_secret:.*$', 'registration_shared_secret: "' + secrets.token_hex(32) + '"', s, flags=re.M)
s += '''
public_baseurl: "http://127.0.0.1:18028/"
enable_registration: false
max_upload_size: 10M
url_preview_enabled: false
rc_message:
  per_second: 100
  burst_count: 1000
rc_registration:
  per_second: 100
  burst_count: 1000
rc_login:
  address:
    per_second: 100
    burst_count: 1000
  account:
    per_second: 100
    burst_count: 1000
  failed_attempts:
    per_second: 100
    burst_count: 1000
'''
p.write_text(s)
p.chmod(0o600)
PY
fi
docker run -d --name "$name" --label element-x.interactive-ui.workspace="$PWD" \
    --user "$(id -u):$(id -g)" -p 127.0.0.1:18028:8008 -v "$sandbox:/data" "$image" >/dev/null
for ((i=0; i<60; i++)); do
    if curl --silent --fail http://127.0.0.1:18028/_matrix/client/versions >/dev/null; then
        echo "Dedicated Synapse ready: $image"
        exit 0
    fi
    sleep 1
done
echo 'Synapse readiness timed out; inspect dedicated container logs locally' >&2
exit 1
