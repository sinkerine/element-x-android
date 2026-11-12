<!--
Copyright 2026 Element Creations Ltd.

SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
Please see LICENSE in the repository root for full details.
-->

# Stable reading while an answer grows

Android implementation based on `bc37934daa`, integrated with local develop's answer/source navigation at `d48e56f14e`. Enabled by default, with no new setting, strings or protocol fields.

## Scope and existing implementation

Room and thread views share `TimelineView`, a reversed `LazyColumn` keyed by SDK unique event IDs. The default text renderer is `EditorStyledText`, an Android text view with an `android.text.Layout` callback. The separate Compose HTML renderer is disabled by default and was left disabled throughout verification. This change does not infer logical answer identity from sender, time, replies, or threads, and needs no Hermes or Matrix protocol extension.

Images already reserve an aspect-ratio box before decoding. Native maths contributes line metrics during measurement through `MathsSpan`. These protections are distinct from handling subsequent changes in measured size. Interactive cards also have existing status/receipt geometry tests. Event boundaries and actions remain intact.

## Reproduced failures

Three tests using the actual default `TimelineView` fail on the unchanged base:

- At index 1 with a 30px manual scroll offset, another participant's new event pulls the reader to latest. Auto-follow previously accepted any of the newest three items, regardless of pixel offset or item size.
- A manual 30px scroll within item 0 leaves the latest button hidden.
- Forcing that button visible and clicking it leaves the same 30px offset. Navigation ignored item 0's offset.

A native-renderer test also reproduced **1,320px** of movement after appending 20 passages to a 30-passage event while reading earlier text. It uses `SpannedString` with the real `EditorStyledText` Android text view and native measurement inside the actual `TimelineView`. Inspection mode avoids Rust HTML conversion; the alternate Compose HTML renderer is explicitly off. Ordinary Compose tests normally skip this native renderer through `LocalUiTestMode`; these tests enable it.

The first recovered emulator run confirmed zero-pixel movement through 30-to-50-to-60-passage Matrix edits, but then exposed a further implementation defect: independent chunks pulled the reader to latest. A bottom decision remembered when the newest key was first composed could outlive a later manual scroll. A regression now separates new-event classification from the corresponding keyed-list update. The scroll helper checks the current bottom boundary; the reading-position controller preserves bottom-follow across list changes. The corrected build passes the previously failing real Matrix sequence.

## Implementation

`TimelineReadingPosition` retains the visually topmost event and the first nonblank native reading line starting in the viewport, falling back to the last clipped line or the row's top. It stores a character offset and short text prefix independently of Android's mutable layout objects. After an edit, it resolves the prefix at that offset, or a unique occurrence in the same text slot. Missing or ambiguous text falls back to the saved row position.

Rows retain stable SDK keys. Body and caption slots survive `ContentAvoidingLayout` replacing a measurement subcomposition, so restoration does not read the outgoing text view's still-attached coordinates. Coordinates are registered during placement. Global positioning performs synchronous scroll compensation before drawing, without starting an animation. A large edit that evicts the event from the lazy window first restores its key and then refines its measured position.

Manual scrolling and viewport-size changes capture a fresh anchor. Explicit focus, unread navigation and return-to-latest reset it. Same-event growth and successive new messages follow when already at the bottom. The latest button appears when the actual pixel boundary is left and handles a nonzero item-0 offset. The existing behavior of following the user's own newly sent message is retained.

Media captions participate in native text anchoring, including image-height changes above a caption. Maths continues using native line metrics. Non-text content, including interactive cards, uses row-top anchoring. The card status regression checks physical top (`offset + size` in the reversed list), rather than requiring its bottom-relative offset to stay fixed when a retry control disappears.

## Final validation

Raw evidence is local under ignored `build/e2e/stable-reading/`.

- **743 tests, 0 failures, 0 errors, 1 existing skipped test**, across 81 messages implementation suites. This includes 9 native timeline reading tests, 6 text-anchor tests, and the added classification/list-order regression. Edit fixtures set `isEdited = true`; lazy-window eviction, removal of the reading event, delayed native style/maths metrics, image dimensions and existing card-layout cases pass.
- `:app:assembleGplayDebug`, `:features:messages:impl:lintDebug`, and `:features:messages:impl:ktlintCheck` pass on the final source. Android lint reports 48 `MissingQuantity` warnings in untouched translations, with no errors. `git diff --check` passes.
- Final combined build, full messages tests, formatting and Android lint: `recovery-verified-build.log`, successful in 1m 35s. The final explicit style check is `recovery-ktlint-check.log`. Earlier baseline and failed regression logs are retained separately.
- Authenticated Matrix emulator verification passes on the Gplay x86_64 debug APK, API 33, dedicated Pixel 2 AVD `stable_reading_a1`, serial `emulator-5596`. Three synthetic participants and one dedicated unencrypted room use this workspace's Synapse on loopback port 18048. No personal rooms or devices are involved.
- The APK was installed with `adb install -r --no-incremental`. `firstInstallTime` (2026-11-11 23:00:26) precedes `lastUpdateTime` (2026-11-12 00:05:13), confirming an in-place update. APK/AAR hashes are retained in `recovery-artifact-sha256.txt`.

### Matrix journeys

The final run uses actual room/thread navigation, standard `m.replace` edits, independent message events and a second sender. App UI markers confirm updates arrived before assertions. After chunks from two senders, another edit to the visible answer acts as a delivery barrier before comparing screenshots; returning to latest then confirms those chunks are visible.

| Journey | Observed result |
| --- | --- |
| Room: read earlier text, edit 60→70→80 passages, send independent chunks from two senders | Same rendered passage stays at exactly **0px** vertical movement |
| Room: manually scroll again, insert a display formula above the reading passage | New reading position stays at **0px** movement |
| Thread: read earlier text, edit 20→35→50 passages, send chunks from two senders | Same rendered passage stays at **0px** movement |
| Room and thread: return to latest, then send another event | Latest events visible; bottom-follow resumes |
| Room: grow the newest long event from 10→20 passages while at bottom | Final passage and edited marker visible at the bottom; latest button stays hidden |
| Image with long native caption: edit dimensions from 800×200 to 800×1600 and add an update marker at the caption end | Earlier caption passage stays at **0px** movement after the update reaches the app |
| Interactive card: grow 30→50 passages and update a visible paragraph below the measured passage | Earlier passage stays at **0px** movement; visible marker confirms the new revision rendered |

Seven final screenshot-template comparisons locate the same text at the same vertical pixel, with correlations from 0.999998 to 1.0. The templates include passage numbers to distinguish repeated prose. Measurements are in `passage-measurements.jsonl`; final successful images start with `fixed-`. The earlier failed chunk comparison is retained as failure evidence and is not counted as a measured displacement. These are E2E evidence images, not recorded screenshot-test baselines.

The media journey validates a later Matrix edit that changes reserved dimensions, not every decoder's asynchronous behavior. The maths journey validates a later formula insertion and its native layout change. Card growth retains the row's position; it does not promise anchoring individual controls inside a rearranged card.

## Environment recovery and cleanup

The initially available local SDK AAR compiled but crashed on x86_64 because its native library lacked `uniffi_matrix_sdk_ffi_fn_method_timeline_send_reply_with_extra_content`. SDK commit `96d0135495d68dd76a80510a41a86e2f90270490` was built in an isolated detached worktree below the ignored evidence directory using a private copy of existing build outputs. Only this Android worktree's AAR was repaired; main checkout dependencies stayed read-only. No SDK source change is needed for this feature.

The emulator initially segfaulted in its host `RenderThread` with `swiftshader_indirect` and with `swiftshader`/Vulkan disabled. The sibling navigation workspace identified the supported recovery configuration: **`-gpu swangle -feature -Vulkan`**. Reusing the same AVD with those flags completed both recovered stable-reading sessions without another host crash. The original blocker is resolved for this run.

Heavy builds/tests and emulator sessions use the absolute common Git directory's `e2e-feature-build.lock`, plus the local Gradle wrapper's lock. Work was coordinated with the sibling workspace and sessions were handed off without overlap. No screenshot baselines were recorded. The final emulator was shut down cleanly, its adb reverse removed, and this workspace's Synapse container stopped. AVD/session data and fixtures are preserved. No existing session was cleared.

## Combined develop validation

The final integration registers distinct fragment slots in `AnswerNavigationView`: `answer:introduction`, `answer:section:<ordinal>:heading`, and `answer:section:<ordinal>:body`. `TimelineItemTextView` accepts and forwards the stable reading key, native `Layout`, and placed coordinates. Duplicate heading names stay independent. The navigation viewport callback and forward-jump passage rectangle are preserved.

Combined validation passes **767 tests, zero failures/errors, one existing skip**, including new native room/thread regressions that grow fragments before and after the selected passage, add later messages, and remove navigation by editing to a short body. The combined debug APK, Android lint and detekt pass (`integration/checks-2.log`). Detekt explicitly allowlists the per-row reading context and the composable follows the repository's modifier/parameter conventions.

The existing 23-action/check navigation flow passes on the combined APK in both room and thread timelines, including backward/forward jumps, duplicate labels, current-section re-entry, dismissal and a browser round trip to the actual fixture server. In both timelines, growing earlier and later sections and adding two senders' chunks retains the same passage at exactly **0px** displacement in four further image comparisons, each with correlation 1.0. These use real Matrix edits and distinct per-row fragment slots. Raw evidence is under `integration/`; the dedicated emulator and server were stopped and reverse ports removed afterward.

Before workspace archival, evidence, unit XML results, the verified APK/AAR and the dedicated AVD/session are preserved in the common Git directory at `.git/e2e-evidence/stable-answer-reading/`. The archived AVD registration points to its preserved location. Build caches and the isolated SDK compilation tree are excluded; the SDK source revision and verified AAR remain recorded.

## Limits and integration details

Native anchoring is at rendered-line granularity, not a guarantee to retain an arbitrary word through rewriting, font changes or viewport reflow. If an edit removes the line or makes recovery ambiguous, row anchoring is the fallback. Internal rearrangement inside a non-text card retains its row top; individual controls and paragraphs are not separate anchors.

The answer/source-navigation fragment integration is included and verified as described above. Fragment identity uses introduction/section ordinal/heading/body roles, never heading text, rendered text or a random key. The existing caption keeps its separate `caption` slot. No Hermes change or answer-continuation protocol was implemented.

The full repository test suite was not run; automated checks cover the messages implementation, its lint dependencies and the debug app build. The feature and navigation integration are committed for local develop. No pushes, PRs or personal-device installs are performed.
