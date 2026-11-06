# LaTeX Android verification

## Final verification after baseline repairs

**The required full verification is now green.** After the completion audit identified the outstanding repository checks, the user explicitly requested repairing them rather than accepting exceptions. The independently reviewed repair preserves state initialization and cancellation behavior, fixes preview/DI/license conventions and all 19 detekt diagnostics, and makes the Interactive UI disabled-flag test explicitly disable its flag without changing the enabled product default. The implementation rename to `DefaultCrossRoomThreadsService` preserves its binding and session scope. No checks were disabled and no blanket suppressions were added.

The parent's final command ran all unit tests, Konsist, detekt, ktlint, Android lint, Licensee, Gplay/Fdroid Kotlin compilation and Gplay APK assembly with `--continue`: **BUILD SUCCESSFUL**, exit **0**, 13,672 actionable tasks. Evidence: `build/latex-baseline/parent-full-verification.log` and `.exit`. The final XML inventory records **4,789 tests, zero failures/errors, 16 skips** (`parent-final-unit-results.json`); the worker's affected-module run covered 1,318 tests with zero failures and three skips. Independent review found no Critical, Important or Minor findings.

Media fixtures were temporarily hydrated from the existing local Git LFS cache, with SHA-256 verification, for the complete passing regression run. Every original pointer file was restored and verified afterward; fixture assets are excluded from the repair commit. These results supersede the historical baseline failures below. The two complete 15-step emulator journeys remain valid evidence for the LaTeX behavior; the subsequent baseline repair does not alter maths rendering code.


**All 15 maths journey steps passed, and T5 is accepted after tooling and final evidence review.** The journey follows [latex-plan.md §5](latex-plan.md#5-e2e-journey), using the dedicated sandbox and preserving Alice's existing session. Tooling review reported no findings. This document update inspects the retained evidence; it does not rerun the device journey or start services.

The tested app source was `587b1f317df8e0f60e726136f57ce89c1958fe95` with uncommitted T5 fixture/tooling changes. Accepted tasks are T1 `4d7bca4c80`, T2 `587b1f317d`, T3 `9dfb5f93fc` and T4 `81ad648938`; cleanup commits `77c279b313` and `e39b8049dd` are in the ancestry. Local inventories are under `build/e2e/latex/preflight/`; the executed journey and final observations are under `build/e2e/latex/journey/`. Raw artifacts remain ignored and local. No screenshot-test baselines were recorded.

## Tested package and installation

The dedicated device was AVD `interactive_ui_a1`, `emulator-5584`, API 33, x86_64. The package was `io.element.android.x.debug`, Gplay debug, version `26.10.9` (`202610094`). The parent installed the current x86_64 APK once using `adb -s emulator-5584 install -r --no-incremental`. `journey/install.log` records Success; `journey/package-after-install.txt` records `firstInstallTime=2026-11-03 18:36:05` and `lastUpdateTime=2026-11-05 20:30:18`, confirming an in-place update. Alice's retained authenticated session was preserved; app data was not cleared.

The installed build's x86_64 APK is **386,582,973 bytes**, SHA-256 **`5d12c93a24d3f379dc2360d348306a4fdcf12c9c04f2677ddcef4d38b19df0b3`**, as recorded in `preflight/feature-apks-final.json`. That final manifest supersedes the earlier APK hashes and pending statuses in `preflight/verification-checkpoint.json`. The Rust SDK AAR SHA-256 is `1ac85d968e24cc7a78588bbda72b273aca0fe5b81959a59371b9a739899f932d`. Inspection of the final x86_64 APK confirms `lib/x86_64/libratex_ffi.so` and `assets/fonts/KaTeX_Main-Regular.ttf` are packaged. Package metadata and signing inspection are retained in `preflight/apk-badging.txt` and `preflight/apk-signing.txt`.

The size comparison against base `5dfe456ef6db866dbd9b3ba5438e7c88509ea1bc` is recorded in `preflight/apk-size-delta.json`; the base build succeeded (`build/latex-base-assemble.log`, exit 0). These are debug APK sizes, not release distribution sizes.

| ABI | Base bytes | Feature bytes | Increase bytes |
| --- | ---: | ---: | ---: |
| arm64-v8a | 375,054,780 | 379,453,061 | 4,398,281 |
| armeabi-v7a | 98,024,442 | 101,537,989 | 3,513,547 |
| x86 | 98,416,915 | 99,603,857 | 1,186,942 |
| x86_64 | 381,807,863 | 386,582,973 | 4,775,110 |
| universal | 676,182,103 | 686,495,157 | 10,313,054 |

The accepted x86 limitation remains: RaTeX has no 32-bit x86 native library, so those devices use the fallback. This journey verifies x86_64 only.

## Executed journey

`journey/journey-outcomes.json` records all 15 PASS results and cleanup actions. Screenshot and XML names below are relative to `build/e2e/latex/journey/`. The parent observed each result during execution; the final screenshots and XML were also inspected when preparing this record. `bot/sent.jsonl` contains 11 server-fetched event observations, all with `wire_type: m.room.encrypted` and `encrypted: true`, independently confirming encrypted server delivery. It contains event IDs and wire metadata, without message bodies. The received formulas on Alice's device provide the rendering observations.

| Step | Result | Observed behavior and final evidence |
| --- | --- | --- |
| 1 | PASS | Labs and three developer pages expose no maths toggle. The pre-existing New timeline event renderer option starts off. `labs.xml`, `developer.xml`, `developer-lower.xml`, `developer-bottom.xml`. |
| 2 | PASS | Native Euler glyphs, raised superscript and π appear inline with surrounding text on the baseline, without a code box. Accessibility retains the TeX. `spec-inline.png`, `room.xml`. |
| 3 | PASS | The integral and fraction occupy a separate display block; the timestamp does not overlap the formula. `spec-blocks.png`, `spec-blocks.xml`. |
| 4 | PASS | Legacy rendering scales the wide polynomial to fit inside the bubble, with the final term through index 40 visible and no right clipping. `spec-blocks.png`, `spec-blocks.xml`. This is an observed fit assertion; no numeric native em-width measurement is claimed. |
| 5 | PASS | Maths renders within the bullet, quote and bold structure. Explicit red is preserved while other maths uses the primary text colour. `spec-blocks.png`. |
| 6 | PASS | Plain-body dollar/backslash inline maths and the display sum render, preserving the surrounding text. `detection-fallback.png`. |
| 7 | PASS | CommonMark HTML renders the energy expression and thin-spaced product inline, and the fraction as a display block; lossy HTML delimiter text is replaced using the raw source. `detection-fallback.png`. |
| 8 | PASS | Currency, escaped dollars and inline code remain literal; none is typeset. `detection-fallback.png`. |
| 9 | PASS | Invalid TeX and the forbidden macro use code fallbacks without breaking the message. App PID is 4913 both before and after. `detection-fallback.png`, `pid-before-invalid.txt`, `pid-after-invalid.txt`. |
| 10 | PASS | Dark mode changes default maths to light glyphs while preserving explicit red. Light mode is restored. `dark.png`, subsequent light screenshots and cleanup record. |
| 11 | PASS | The actual Copy text action is pasted into the composer without sending. The EditText value in `copy-text-verified.xml` exactly equals the raw Euler fixture body; the composer is subsequently cleared. |
| 12 | PASS | The CommonMark message's reply preview contains plain text and opens without a crash. It is cancelled without sending. `md-reply-preview.xml`, cleanup record. |
| 13 | PASS | With the Compose renderer enabled, spec, structured, plain-detected, CommonMark and fallback cases render equivalently. The wide display scrolls internally from its beginning to the final term at index 40. `compose-room.png`, `compose-wide-end.png`. |
| 14 | PASS | The new renderer is disabled again and legacy rendering returns. `renderer-restored.xml` records the switch false; `legacy-restored.png` shows the restored timeline. |
| 15 | PASS | After sending the synthetic canary, bounded app-PID logcat contains zero marker matches. `canary-check.json` records PID 4913, 401 captured lines for the `-t 400` request, and `marker_matches: 0`. Raw logcat remains local. |

The early copy/forward sheets, `copy-debug.png` and exploratory copy/readback artifacts are unsuccessful automation history and are not pass evidence. Step 11 specifically relies on `copy-text-verified.xml`, whose composer value was checked for exact equality. Step 12 relies on `md-reply-preview.xml`, not an earlier reply attempt. The copied fixture text and raw log bodies are intentionally not reproduced here.

## Build, tests and baseline limits

The repository is **not fully green**. The following results distinguish accepted app checks from existing regression and convention failures. Logs are under `build/` unless a preflight path is specified.

| Check | Result and evidence |
| --- | --- |
| Gplay and Fdroid Kotlin compilation; Gplay assembly | The tasks complete in `latex-accepted-verification.log`, including packaging and assembly of the final APKs. The aggregate command exits 1 because of the separate messages test failure below; this is not an aggregate successful-build claim. Earlier `latex-app-compile.log` and `latex-assemble.log` also record successful builds. |
| Licensee | `latex-licensee.log` records BUILD SUCCESSFUL with the existing allowlist. |
| Android lint | `latex-lint-accepted.log` records BUILD SUCCESSFUL; `latex-lint-accepted.exit` is 0. |
| Maths API | 11 tests, no failures/errors/skips. `preflight/accepted-unit-results.json`. |
| Maths implementation | 61 tests, no failures/errors/skips. Same accepted unit inventory and `latex-accepted-verification.log`. |
| Matrix UI | 85 tests, no failures/errors/skips. Accepted unit inventory. |
| HTML renderer API / implementation | 4 / 25 tests respectively, no failures/errors/skips. Accepted unit inventory. |
| Full messages module | 714 tests, one failure and one skip. `TimelineItemContentInteractiveUiFactoryTest` fails its “flag off local and non-text never read debug info” case because Interactive UI defaults enabled. This is the existing enabled-default expectation mismatch, not a maths test pass. Accepted unit inventory and `latex-accepted-verification.log`. |
| Broad regression | `latex-full-regression.log` records the same messages failure plus eight media preprocessing failures caused by LFS pointer fixture bytes. The media diagnostic retest with real LFS objects passes (`latex-media-fixture-retest.log`, exit 0), after which the fixture state was restored. This diagnostic does not turn the original broad regression into a pass. |
| Konsist | 50 tests, seven failed (`latex-konsist-final.log`, exit 1). `preflight/convention-failures.json` identifies the affected files and marks each unchanged from base: thread/home state, preview, DI and license conventions. |
| Static checks | `latex-static-post-cleanup.log` remains failed with 19 detekt findings in unchanged migration, threads, design-system, Matrix and messages files. `preflight/static-findings.json` inventories 19 unchanged findings plus three earlier sanitizer findings; the sanitizer findings were repaired before the final static run and must not be counted as remaining. No claim of a clean repository-wide static check is made. |
| Fixture tooling | The implementation launch reported `npm ci && node --test`: nine passed, zero failed, including four maths tests; Node and Bash syntax checks and `git diff --check` passed. Tooling review subsequently reported no findings. The parent reran `npm ci && node --test` after the evidence update: `build/latex-t5/parent-final-selftests.log` records nine passing tests and zero vulnerabilities. Final Node and Bash syntax checks also passed. The implementation's `selftests.log`, `syntax.log` and `diff-check.log` are also retained under `build/latex-t5/`. |

The CommonMark checks use pinned dev dependency `commonmark@0.31.2` and Python 3's standard HTML parser for parsed spec attributes and balanced HTML. Runtime maths fixture sends use the existing encrypted bot path. The prior [Interactive UI verification](interactive-ui-verification.md) supplies historical sandbox and baseline context; its device passes are separate from the maths results above.

## Cleanup and delivery limits

The parent recorded the renderer restored to false, night mode restored to `no`, composer cleared and reply cancelled. Bot shutdown returned `stopped: true`; the adb reverse was removed; the ownership-checked Synapse shutdown completed; and `emu kill` was requested for the AVD started by this journey. The retained accounts, Synapse data and AVD storage were preserved.

Post-cleanup artifacts are `journey/postcleanup-ports.json`, `postcleanup-container.txt` and `postcleanup-devices.txt`. The device listing has no connected devices and the container artifact is empty. The parent confirmed the ports artifact's predicate: each true value means a bounded `connect_ex` attempt failed, so ports 18028, 18029, 5584 and 5585 are closed. The cleanup action record must not be treated as stronger proof than those final checks.

Final evidence review found no Critical or Important issues; its Minor stale-log note was corrected before T5 acceptance. No app or tooling code was changed for this record update. Known limitations L2–L6 in the plan remain accepted. Delivery has an additional repository-policy constraint: T2 currently adds 561 production lines across the detection sources, above the 500-addition PR limit. If delivering through PRs, split it into coherent reviewable changes before publication; the executed feature verification does not waive that limit. No commit, PR or publication was performed here.

## Postmerge rerun at e5dcfb3932

**2026-11-05: all 15 journey steps passed again on merge commit `e5dcfb393252348a21e5b3b70748eb2862b1b09b`. Result: DONE_WITH_CONCERNS**, because the full-plan Gradle command retains the baseline failures described below. This is a fresh executed journey after the upstream merge, with a newly built and installed APK and a new encrypted fixture room. Historical `journey/` artifacts were used only for comparison. All paths in this section are relative to ignored `build/e2e/latex/postmerge/`, unless stated otherwise. No app, fixture, tooling source, or screenshot-test baseline was changed.

The full-plan command was allowed to finish before installation: `build/latex-postmerge-checks.exit` contains 1, and `build/latex-postmerge-checks.log` records eight failed tasks. Inspection confirmed that `:app:assembleGplayDebug`, both Gplay and Fdroid Kotlin compilation tasks, `:app:testGplayDebugUnitTest`, and `:app:lintGplayDebug` completed without a FAILED suffix; Licensee was UP-TO-DATE. `build-task-evidence.txt` retains those task lines. The aggregate failure was not treated as an app assembly failure.

The installed x86_64 Gplay debug APK, `app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk`, is **391,366,715 bytes**, SHA-256 **`988e20cc21078a987063d357df521f961d59221828ef18b45b2b2f508af7a5f5`** (`apk.sha256`). `apk-native-assets.txt` confirms the RaTeX native library and KaTeX main font. The dedicated AVD was `interactive_ui_a1`, serial `emulator-5584`, API 33, x86_64 (`device-identity.json`, `avd-start.log`). Stable device availability, boot completion, AVD name, API and ABI were checked before the single `adb install -r --no-incremental`. `install.log` records Success; `package-after-install.txt` records `firstInstallTime=2026-11-03 18:36:05` and `lastUpdateTime=2026-11-05 22:14:42`. Alice's authenticated session and app data were preserved.

Synapse ownership was checked against this worktree before reuse (`synapse-owner.txt`). The fresh bot was kept alive throughout the journey, and `setup-room.json` records creation of a fresh encrypted room. `bot/sent.jsonl` contains ten sends, each independently fetched from the server with `wire_type: m.room.encrypted` and `encrypted: true`. The new room invitation was accepted in Alice's existing session. The identity-reset notice from the new bot device was dismissed; no accounts were re-provisioned.

| Step | Result | Fresh observed outcome and evidence |
| --- | --- | --- |
| 1 | PASS | Labs and developer pages have no maths toggle; the pre-existing New timeline event renderer starts false. `labs.xml`, `developer.xml`, `developer-lower.xml`, `developer-bottom.xml`. |
| 2 | PASS | Euler identity has raised superscript and π, on the surrounding text baseline without a code box; accessibility retains the TeX. `spec-inline.png`, `spec-inline.xml`. |
| 3 | PASS | Integral and fraction render as their own display block with a separate, non-overlapping timestamp. `spec-blocks.png`, `spec-blocks.xml`. |
| 4 | PASS | The legacy wide polynomial is scaled inside the bubble, with its final term at index 40 visible. Its text bounds are retained in the live hierarchy. `spec-blocks.png`, `spec-blocks.xml`. |
| 5 | PASS | Bullet, quote and bold maths render; explicit red remains red and other maths uses primary colour. `spec-blocks.png`. |
| 6 | PASS | Plain-body π r² and square root render inline, with the sum in a display block and surrounding text intact. `detection-fallback.png`. |
| 7 | PASS | CommonMark energy and thin-spaced product render inline, and the fraction renders as a block; the lossy literal HTML delimiter text does not remain. `detection-fallback.png`. |
| 8 | PASS | Currency and escaped dollars stay literal and inline code stays code, with no unintended typesetting. `detection-fallback.png`, `detection-fallback.xml`. |
| 9 | PASS | Invalid fraction and forbidden macro use code fallback, preserving surrounding text. PID is 4392 before and after. `detection-fallback.png`, `pid-before-invalid.txt`, `pid-after-invalid.txt`. |
| 10 | PASS | Default maths becomes light on dark; explicit red stays red. Light mode is restored. `dark.png`, `restoration-check.json`. |
| 11 | PASS | Copy text is pasted into the focused composer without sending; the actual EditText value exactly equals the raw Euler fixture body. The composer is then cleared. `copy-text-verified.xml`, `copy-check.json`, `composer-cleared.xml`. |
| 12 | PASS | Reply to the CommonMark message opens a plain-text preview without crashing, then is cancelled without sending. `md-reply-preview.png`, `md-reply-preview.xml`, `final-room.xml`. |
| 13 | PASS | Renderer option is true; Compose renders spec inline/display, structured, plain-body detection, CommonMark and both fallbacks. The wide block scrolls horizontally to the final term at index 40. `renderer-enabled.xml`, `compose-room.png`, `compose-wide-end.png`, `compose-wide-end.xml`. |
| 14 | PASS | Renderer option is restored false and legacy rendering returns unchanged. `renderer-restored.xml`, `legacy-restored.png`. |
| 15 | PASS | The fresh canary is received on-device; PID-filtered logcat requested with `-t 400` captures 401 lines and zero marker matches. `canary-received.xml`, `canary-check.json`. Raw logs remain local and were not printed. |

The screenshots above were inspected during execution; the results are not inferred from successful taps. `journey-outcomes.json` inventories each pass and its evidence. Initial copy attempts failed to establish paste equality, including a system Nearby Share overlay that was dismissed without proceeding. The final copy result relies only on the exact equality assertion in `copy-check.json` and the corresponding composer XML. An initial horizontal gesture selected a non-scrollable display and opened a message sheet; it was dismissed. Subsequent gestures targeted the live **scrollable** HorizontalScrollView and reached term 40. Those earlier attempts remain local automation history, not pass evidence. Live sheets were dumped twice before selecting actions, and the Copy text sheet was expanded to put that action clear of the system navigation area.

The full-plan postmerge checks remain **failed overall**, with no newly observed failure relative to the retained premerge baseline:

| Check | Postmerge result and comparison |
| --- | --- |
| Maths API / implementation | 11 / 61 tests, zero failures, errors or skips. |
| Matrix UI; HTML renderer API / implementation | 85; 4 / 25 tests, zero failures, errors or skips. |
| Messages | 714 tests, one failure, one skip. Same `TimelineItemContentInteractiveUiFactoryTest` enabled-default expectation mismatch as before. |
| Media upload | 37 tests, eight failures, nine skips. Same `AndroidMediaPreProcessorTest` cases associated with the retained LFS pointer test media. No fixture bytes were replaced or hydrated for this rerun; the earlier diagnostic LFS retest is not counted as a current pass. |
| Konsist | 50 tests, seven failures. The same preview, state, naming, DI and license convention test identities remain. |
| Detekt | Five failed module tasks, with 19 diagnostics. `static-comparison.json` compares diagnostic paths, line positions and messages with `build/latex-static-post-cleanup.log`: all 19 match, zero new or removed. |
| Assembly, compilation, Licensee, Android lint, app unit tests | Completed as described above in the aggregate command; no additional failed task is reported for these checks. |
| Fixture bot self-tests | Fresh `node --test` run: nine passed, zero failed (`fixture-tests.log`), using the existing installed dependencies. |

`unit-results.json` records counts and failed test identities; `failure-comparison.json` records the comparison and confirms that the messages, media-upload and Konsist test sources were unchanged by the upstream merge. These comparisons preserve the baseline failure classification and do not claim that the repository is green. The original full-plan log and exit marker remain under `build/`.

Cleanup was executed and independently checked. `restoration-check.json` confirms renderer false, night mode `no`, empty composer, cancelled reply, encrypted server delivery and stable PID for the fallback check. `bot-shutdown.json` records `stopped: true`; the adb reverse was removed, the ownership-checked Synapse shutdown completed, and only the AVD started by this journey was stopped (`reverse-remove.txt`, `synapse-down.txt`, `owned-avd-before-shutdown.txt`, `avd-shutdown.txt`). `postcleanup-devices.txt` lists no devices, `postcleanup-container.txt` is empty, and `postcleanup-ports.json` explicitly records `closed: true` for 18028, 18029, 5584 and 5585. Accounts, Synapse data, AVD storage and Alice's app session remain retained. No commit, branch merge into the main checkout, PR or publication was performed by this rerun.
