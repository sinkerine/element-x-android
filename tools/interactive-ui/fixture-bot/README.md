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

`sent.jsonl` records the actual event type fetched from Synapse after sending, and rejects a plaintext send outside the separately created plaintext gallery fixture room. `actions.jsonl` is emitted only after the bot sees the action content; it records `wire_type`, decrypted type, action IDs/values, mentions and relations. Nonces are reduced to length and base64url checks. Counts deduplicate server event IDs; use an Alice sender filter for zero-event assertions because bot sends and edits also count. Marks cover events observed after the mark, not arbitrary server history. SDK logs remain local and are not a redaction-safe evidence API; inspect before sharing. Control errors intentionally return only exception names.

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

Evidence accepts only descendants of `build/e2e/interactive-ui/`, `build/e2e/latex/` and `build/e2e/mixed-file-gallery/`; traversal and similarly prefixed directories are rejected. Keep raw screenshots, UI dumps and logcat local under ignored `build/e2e/latex/<UTC>/`, inspect before sharing, and never record screenshot-test baselines locally. Sandbox startup resolves the tracked repository/worktree rather than requiring a branch name, retaining occupied-port, dedicated-AVD and container-ownership safeguards. Stop Synapse only with this worktree's `synapse-down.sh`; restore theme and renderer settings, remove the adb reverse, shut down the bot, and stop only an AVD started by the journey.

## Mixed-file gallery fixtures

`send-gallery mixed` supplies `info.thumbnail_file` and `info.thumbnail_info` on both PNG image items, as consumed by the SDK's image thumbnail mapping. The tiny 96×64 original image upload is also the thumbnail: its encrypted descriptor, dimensions, MIME type and byte size are valid for both uses. This keeps fixture bytes and the four original uploads unchanged while allowing thumbnail-dependent clients to render the mixed gallery as a grid plus rows. The fixture regression decrypts each thumbnail descriptor from the actual uploaded bytes; real encrypted receiving and final grid observations remain verifier checks.


These commands support the [approved mixed-file gallery plan](../../../docs/features/mixed-file-gallery-plan.md), including C0 against the unchanged base app. No Android changes are required by this tooling. Run from the feature worktree root. Use a **new isolated state directory** for every `provision`, `serve` and `ctl` invocation in this journey:

```sh
export ELEMENT_X_E2E_STATE_DIR="$HOME/.local/share/element-x-e2e/mixed-file-gallery"
node tools/interactive-ui/fixture-bot/bot.mjs device-fixtures --out build/e2e/mixed-file-gallery/device-fixtures
# After the verifier has started the dedicated Synapse sandbox:
node tools/interactive-ui/fixture-bot/bot.mjs provision
node tools/interactive-ui/fixture-bot/bot.mjs serve --evidence build/e2e/mixed-file-gallery/<UTC>
# Keep serve running; use another terminal with the same state-dir export:
node tools/interactive-ui/fixture-bot/bot.mjs ctl setup-room
node tools/interactive-ui/fixture-bot/bot.mjs ctl setup-room --plain
node tools/interactive-ui/fixture-bot/bot.mjs ctl mark
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-gallery mixed
node tools/interactive-ui/fixture-bot/bot.mjs ctl send-gallery mixed --thread '<root-event-id>'
node tools/interactive-ui/fixture-bot/bot.mjs ctl count-events --since mark --sender alice
node tools/interactive-ui/fixture-bot/bot.mjs ctl gallery-last --since mark --sender alice --caption 'Q3 docs'
node tools/interactive-ui/fixture-bot/bot.mjs ctl gallery-last --room plain --sender alice --caption ''
node tools/interactive-ui/fixture-bot/bot.mjs ctl gallery-last --replace --sender alice --caption 'Q3 docs v2'
```

`ELEMENT_X_E2E_STATE_DIR` overrides only local account credentials and the control token; without it the existing `~/.local/share/element-x-e2e/interactive-ui` default is unchanged. The directory is mode 0700 and secret files are 0600 when written. **Never read, reuse or delete the earlier `interactive-ui/credentials.json` for this journey.** Keep the same export in every terminal. This does not change the dedicated Synapse data path or create/start any stack.

`device-fixtures --out <dir>` runs without a server, login or credentials. It deterministically writes `red.png` and `green.png` (96×64 RGB PNG), `report.pdf` (one page), `notes.txt` (`mixed-file-gallery notes` plus newline), `tone.wav` (one second of 440 Hz, 8 kHz, 16-bit mono PCM), `noext` (bytes 0, 1, 2, 3, 255), and `bulk/bulk-01.txt` through `bulk/bulk-61.txt`. The manifest is `{files:[{name,mime,size,sha256,png}]}`; names are relative to the output directory, sizes are byte counts and SHA-256 hashes are lowercase hex over plaintext. PNG entries add `png:{width,height,rgb_sha256}` computed from the fixture bytes; non-images have `png:null`. Generation overwrites those fixture files and the manifest only. Push the generated directory contents to `/sdcard/Download/mfg/` on the dedicated AVD and trigger a media scan before using DocumentsUI. Keep the manifest on the host to compare device sends with their original bytes for non-images and decoded RGB pixels/dimensions for Files-picker PNGs.

`setup-room --plain` creates a separate private room named **Mixed gallery plain**, invites the dedicated Alice account, waits for room sync and verifies that it is unencrypted. Repeated calls reuse that room during this process. The default `setup-room` and all existing sends retain the encrypted-room requirement, including the actual server wire-event check. The plaintext exception is restricted to this separately created room. Both rooms are observed; `count-events` and `actions` select the default encrypted room unless `--room plain` is supplied. Marks remain process-wide observation boundaries and counts deduplicate event IDs.

`send-gallery mixed [--thread <event-id>]` uploads `[green.png, red.png, report.pdf, tone.wav]` as encrypted `application/octet-stream` media, then sends one Web-shaped `dm.filament.gallery` to the default encrypted room with caption `from bot` and `m.mentions`. The item `body` is its synthetic filename, `info` contains the original MIME type and size, and `file` is a v2 encrypted attachment descriptor. The optional thread relation targets the supplied event. The response and `bot/sent.jsonl` contain only `{event_id,wire_type,encrypted}`; keys and caption text are never returned.

`gallery-last [--room plain] [--since mark] [--sender alice] [--replace] [--caption <text>]` selects the newest matching observed gallery in that room. Other sender IDs and explicit mark IDs work as with `count-events`; unknown rooms/marks and missing option values fail. Ordinary calls skip caption replacements; `--replace` requires an `m.replace` event and projects its `m.new_content`, including its inner thread relation. The separate `replacement_target_event_id` field records the outer replacement target and is required for replacement observations. No matching gallery is an error. Wait for the concrete expected event within the journey's bounded readiness deadline rather than treating a missing observation as a pass.

The projection is `{event_id,wire_type,encrypted,msgtype,caption_length,caption_equals,has_formatted_body,relation:{rel_type,event_id},mentions,items:[{itemtype,body,mimetype,size,has_url,url_is_mxc,has_file,file_v,file_keys_complete,plaintext_sha256,png}]}`. Caption equality is `null` when `--caption` is omitted; an explicit empty caption is supported. Every selected item is downloaded from the homeserver's authenticated Matrix media endpoint with the bot's bearer token; redirects and non-MXC sources are rejected. Encrypted items are verified and decrypted using `decryptAttachment` before hashing. A failed download, failed event decryption, attachment hash mismatch or malformed/unsupported PNG evidence fails the command. Synthetic filenames are intentionally included for fixture matching, but captions, descriptors, keys, tokens and media URLs are omitted. Store control JSON output locally under `build/e2e/mixed-file-gallery/<UTC>/`; the bot does not persist gallery projections automatically. SDK logging remains suppressed.

`png` is `{width,height,rgb_sha256}` for received PNGs and `null` for non-images. It is independently decoded from the actual downloaded/decrypted plaintext, never looked up by name or declared dimensions. Canonical RGB is the sequence of unsigned 8-bit R, G, B samples, left to right and top to bottom, without row filters, padding, metadata or colour-management conversion. `rgb_sha256` is lowercase hex SHA-256 over that sequence. Compare all three fields: reshaping identical RGB bytes into different dimensions must fail. `plaintext_sha256` remains the encoded-file hash for every item, including PNGs, and the manifest's original `sha256` values and fixture bytes are unchanged.

The bounded decoder supports the fixtures' opaque 8-bit truecolour, non-interlaced PNG encoding, all five PNG row filters, consecutive split IDAT chunks and ancillary metadata (including eXIf/text). It validates signature, chunk bounds/order/CRC, zlib consumption and exact decoded length. Alpha, transparency, palettes as pixel encoding, other bit depths, interlacing, animation, unknown critical chunks, oversized images (over 16 Mi pixels), and non-PNG image items fail closed. An absent PNG observation is never acceptable for a fixture image. File items with `image/png` MIME or an actual PNG signature also require decoding.

For amended C0/J2/J5/J6/J10 integrity, assert PNG `width`, `height` and `rgb_sha256` exactly equal the manifest observations, recording both encoded hashes separately. Non-image `plaintext_sha256` must still exactly equal the manifest `sha256`. Continue checking original names/order/types/MIME/size, source encryption/descriptors, counts, caption equality and mentions. This preserves existing image metadata sanitization and compression rules. The two original C0 image-byte experiments remain failed evidence; successful decoded comparisons must be recorded in a new independently observed run under the approved integrity amendment.

The evidence allowlist includes descendants of `build/e2e/mixed-file-gallery/`, alongside the existing `interactive-ui/` and `latex/` roots. Prefixed siblings and resolved traversal outside those roots are rejected. Keep screenshots, UI dumps and projection JSON under that ignored evidence path and inspect before sharing.

For C0, the independent verifier builds/installs the unchanged base app, enables the existing Gallery messages Labs setting, drives Files multi-selection and compares the Alice gallery projection with the manifest. Receiving `send-gallery mixed` should show four flat rows on that base app; record that expected baseline feature failure, then restore Labs off. Tooling tests do not establish C0 or any device journey result. Keep `serve` alive throughout C0 and subsequent journeys: its crypto device, room IDs, observations and marks are in memory.

Cleanup: `ctl shutdown` leaves and forgets both created fixture rooms, remove the dedicated AVD's `tcp:18028` reverse, delete only `/sdcard/Download/mfg/` on that AVD, restore changed settings, and stop only the sandbox/AVD started for this journey. Retain the isolated state directory, Synapse data and AVD for reruns. Preserve Alice's session and the earlier sandbox credentials. Do not clear app data or record screenshot-test baselines.
