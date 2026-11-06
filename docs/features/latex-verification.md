# LaTeX Android verification

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
