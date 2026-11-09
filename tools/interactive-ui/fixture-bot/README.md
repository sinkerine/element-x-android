# Interactive UI encrypted fixture bot

This standalone Node ESM harness uses `matrix-js-sdk` 45.0.0 and its Rust WASM crypto, pinned by `package-lock.json`. It sends Android's vendored fixtures, without importing application code. GPT and Hermes are not involved. Run from the Android worktree root; the canonical Web specification was read at `bcb080171cb312feef37e63007c7ca8cc0a8b77f`.

```sh
(cd tools/interactive-ui/fixture-bot && npm ci && node --test)
nix develop --command ./tools/gradle-local.sh :app:assembleGplayDebug
tools/interactive-ui/sandbox/synapse-up.sh
node tools/interactive-ui/fixture-bot/bot.mjs provision
node tools/interactive-ui/fixture-bot/bot.mjs serve --evidence build/e2e/interactive-ui/<UTC>
# In another terminal:
node tools/interactive-ui/fixture-bot/bot.mjs ctl setup-room
```

`provision` creates only Alice and uibot on `interactive-ui.test`; passwords are random. Credentials and the control bearer token live in `~/.local/share/element-x-e2e/interactive-ui/{credentials.json,control-token}` with mode 0600, in a mode 0700 directory. Never print, stage, or commit them. Provision resumes a partially completed credentials file. Do not delete that file while retaining Synapse's accounts. The bot creates a fresh, in-memory crypto device and bootstraps cross-signing each time `serve` starts; **keep that process alive for the entire journey**. A restart loses room IDs, marks, uploaded descriptors, and the old device's in-memory keys.

The control API binds only `127.0.0.1:18029`, requires `Authorization: Bearer <local token>`, and accepts `POST /ctl` with `{"args":["command","argument",...]}`. The CLI reads the token without printing it. Commands:

```sh
node tools/interactive-ui/fixture-bot/bot.mjs ctl status
node tools/interactive-ui/fixture-bot/bot.mjs ctl send knowledge-map.en
node tools/interactive-ui/fixture-bot/bot.mjs ctl mark
node tools/interactive-ui/fixture-bot/bot.mjs ctl count-events --since mark --sender alice
node tools/interactive-ui/fixture-bot/bot.mjs ctl actions --since mark --sender alice
node tools/interactive-ui/fixture-bot/bot.mjs ctl send controls --set /nodes/submit/action/single_use=true --set '/state/name="Alice"'
node tools/interactive-ui/fixture-bot/bot.mjs ctl revise '<event-id>' --status submitted --status-text 'Your choice was accepted'
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-root 'Thread question'
node tools/interactive-ui/fixture-bot/bot.mjs ctl send form --thread '<root-id>' --set '/nodes/number_input/display="field"' --set /nodes/number_input/max=20
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-invalid bad-type
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-version 2
node tools/interactive-ui/fixture-bot/bot.mjs ctl upload-encrypted-png
node tools/interactive-ui/fixture-bot/bot.mjs ctl send product-carousel --encrypted-images
node tools/interactive-ui/fixture-bot/bot.mjs ctl send content --encrypted-images --corrupt-image --image-offset 5
node tools/interactive-ui/fixture-bot/bot.mjs ctl shutdown
```

Use the returned event IDs for revisions and threads. Each send gets a new `ui_id`; revisions retain that identity and increment `revision`. `--set /pointer=JSON` accepts numeric node indices or `/nodes/<id>/...`. Repeated `--set` arguments patch a cloned fixture without changing vendored files. `controls` has an empty required name, so the single-use journey supplies a name. `form` ships with a slider and maximum 10; the decimal journey patches it to `display:"field"` and maximum 20. Fallback is always `Complete text fallback: <fixture-name>`.

Upload five PNGs for the carousel: red, green, blue, yellow, purple. Images are real RGB PNGs encrypted using AES-256-CTR with a random 256-bit key, a random 64-bit IV prefix, zero counter suffix, and SHA-256 over the ciphertext. The upload uses `application/octet-stream`. Descriptors and keys stay in the bot. Upload **two additional, unused URIs** for the valid/corrupt content scenario; `--image-offset 5` selects them. The corruption variant uses the second URI with an incorrect hash. Before the accepted cache repair, a previously decrypted URI could reuse old cached pixels; the separate URI was introduced to prove rejection of a fresh corrupt attachment despite that historical defect. Current warm/cold same-URI tests now reject altered hash/key/IV descriptors and plain requests for previously encrypted pixels. Keep the distinct-URI scenario as a fresh-attachment control alongside those same-URI checks.

`sent.jsonl` records the actual event type fetched from Synapse after sending, and rejects a plaintext send. `actions.jsonl` is emitted only after the bot sees the action content; it records `wire_type`, decrypted type, action IDs/values, mentions and relations. Nonces are reduced to length and base64url checks. Counts deduplicate server event IDs; use an Alice sender filter for zero-event assertions because bot sends and edits also count. Marks cover events observed after the mark, not arbitrary server history. SDK logs remain local and are not a redaction-safe evidence API; inspect before sharing. Control errors intentionally return only exception names.

Build before starting the emulator to keep memory peaks separate. The local build wrapper serializes builds across worktrees, limits Gradle to two workers, and exits its single-use daemon after the build. The emulator uses 2048 MiB guest RAM and two cores; new AVDs use a Pixel 2 profile, while existing profiles and sessions are preserved.

The dedicated sandbox scripts refuse occupied ports and foreign container ownership. Synapse binds `127.0.0.1:18028`, is pinned to the local image RepoDigest, and retains data in ignored `build/e2e/interactive-ui/synapse`. `avd-up.sh` creates only `interactive_ui_a1`, API 33 Google APIs x86_64, under `~/.local/share/element-x-e2e/avds`, on port 5584. Run it with JDK 21 and the host emulator's shared libraries available. This NixOS host needed `steam-run` plus NSS, NSPR and libxkbfile libraries; local wrapper details are recorded in the ignored checkpoint summary. Install once on the verified dedicated device with `adb -s emulator-5584 install -r --no-incremental` after building; never retry installation in a loop. Use `adb -s emulator-5584 reverse tcp:18028 tcp:18028` for login.

The standalone `.maestro/tests/interactiveUi/interactiveUiJourney.yaml` requires Alice signed in, the encrypted fixture room open, Labs enabled, portrait Pixel 7 Pro layout, and `MAESTRO_INTERACTIVE_UI_BOT_TOKEN` securely forwarded from the local file. Maestro 2.12.1 executed this flow successfully on the dedicated default Pixel layout at HEADs `4bc4f9ab6f` and `82ff2044d3`; see the verification document. It is excluded from `allTests.yaml`, preserves app data and targets steps 1, 2 and 4 only. Revalidate actual device selectors after layout changes. Its graph swipe uses percentages of the inspected default display; it is not a responsive-layout assertion. Forward the token only through the `MAESTRO_` environment; never put it on the command line. Maestro serializes environment values into local metadata, so scan/redact all token occurrences before sharing artifacts.

Normal cleanup is `ctl shutdown` (bot leaves/forgets its room), remove the adb reverse, restore changed device settings and Labs flag, stop Synapse via `synapse-down.sh`, then shut down only the AVD started here. Keep its data for reruns. The final verification pass completed this cleanup. Synapse data, local credentials and the dedicated AVD are retained; a later run must start a new bot process and create a new fixture room.

## Maths fixtures

Maths is always enabled in the Android app. The pre-existing **New timeline event renderer** developer option selects the renderer for comparison; it is not a maths toggle. Follow [the LaTeX plan](../../../docs/features/latex-plan.md#5-e2e-journey) and record observations in [the verification record](../../../docs/features/latex-verification.md). Preserve Alice's existing session, the retained Synapse data and the dedicated AVD. Do not re-provision an existing sandbox or clear application data.

```sh
node tools/interactive-ui/fixture-bot/bot.mjs serve --evidence build/e2e/latex/<UTC>
# Keep serve alive; in another terminal:
node tools/interactive-ui/fixture-bot/bot.mjs ctl setup-room
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-maths spec-inline
```

`send-maths <name>` sends a fresh `m.text` through the same encrypted `sendContent` path as the UI fixtures. HTML fixtures include `format: "org.matrix.custom.html"`; plain fixtures omit both HTML fields. The control response and `sent.jsonl` contain only the event ID, wire type and encrypted boolean, never TeX or message bodies. Unknown names fail before sending.

| Name | Scenario |
| --- | --- |
| `spec-inline` | Euler identity, inline with surrounding text |
| `spec-display` | Integral and fraction in a display block |
| `spec-wide` | Expanded polynomial through term 12, then ellipsis and term 40; measure its actual width on-device |
| `spec-mixed` | List, quote, bold and explicit red TeX |
| `spec-invalid` | Invalid fraction, code fallback |
| `spec-macro` | Forbidden macro definition, code fallback |
| `plain-detect` | Raw inline dollar/backslash delimiters and display sum; no HTML |
| `md-detect` | CommonMark HTML with lossy backslash escaping; raw body retains the TeX |
| `no-maths` | Currency, escaped dollars and inline code, with no typesetting |
| `canary` | Synthetic no-content-logging marker; inspect bounded app-PID logcat locally |

The HTML strings are fixed fixtures, not generated at runtime. Tests use pinned dev dependency `commonmark@0.31.2` to verify the Markdown fixtures (only the renderer's final newline is omitted), and Python 3's standard HTML parser to check balanced HTML and parsed `data-mx-maths` values. Run `npm ci && node --test` in this directory. Production bot execution does not require CommonMark or Python.

Evidence accepts only descendants of `build/e2e/interactive-ui/` and `build/e2e/latex/`; traversal and similarly prefixed directories are rejected. Keep raw screenshots, UI dumps and logcat local under ignored `build/e2e/latex/<UTC>/`, inspect before sharing, and never record screenshot-test baselines locally. Sandbox startup resolves the tracked repository/worktree rather than requiring a branch name, retaining occupied-port, dedicated-AVD and container-ownership safeguards. Stop Synapse only with this worktree's `synapse-down.sh`; restore theme and renderer settings, remove the adb reverse, shut down the bot, and stop only an AVD started by the journey.
