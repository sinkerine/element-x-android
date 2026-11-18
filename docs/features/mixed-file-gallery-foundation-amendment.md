# Proposed Foundation anchor-mutation cleanup backport

Status: **Approved by the user: “approve all iterations”; recorded 2026-11-17 17:23 PST (UTC−08:00) on kazuki.** Routine repair/qualification iterations within this source scope and 4 CPU/12 GiB/four-hour envelope are authorised without repeated confirmation. No Foundation artifact or dependency substitution has yet been applied. Existing feature and four earlier approved amendments remain in force.

## Evidence and required scope

Mixed-gallery Edit caption cannot be activated by native touch after expanding the real sheet. R6 proves the press is unconsumed at Sheet Initial, consumed before List Initial, and reaches the correct row while the sheet incorrectly reports animation active long after settling. The exact Foundation implementation consumes that press when its `dragTarget` marker is non-null.

Main reproduced the dependency defect with public API, without Android timing assumptions: an anchor update while an untargeted mutation owns the lock leaves the marker set after the operation returns. Main's three unchanged desired-invariant tests fail against Foundation1.12.0: normal completion, cancellation without settlement, and a rejected lower-priority targeted operation incorrectly clearing the current owner's marker. The near-full-height wrapper and ordinary resize regressions also fail. Temporary row/sheet diagnostics and local XML/logs retain the native and test evidence.

The evaluated outer-top-inset workaround makes the fixed-size wrapper case pass, but the ordinary630→600dp resize case still fails. Main reverted its production effect. Both independent read-only advisors converge on repairing mutation ownership in Foundation. Published1.12.1 and1.13.0-rc01 sources retain the defect; a blind version upgrade is not proposed.

D10/G12 in the original plan require unchanged dependencies/catalog, except the separately approved Rust SDK logging changes. This Foundation substitution is a new, explicit dependency exception requiring human approval. No gallery acceptance, SDK identity, privacy, screenshot exception, feature default or delivery requirement is relaxed.

## Concrete source patch

Review [anchored-drag-cleanup.patch](../../tools/foundation/anchored-drag-cleanup.patch), SHA-256 **986f2c6434628f96d750de4fe16e3c25dd01c49bc1ef629b3464664a29a09410**. It changes only `AnchoredDraggable.kt` and adds an Apache-2.0 modification notice.

- Untargeted `anchoredDrag`: put existing work/settlement in `try`, and clear `dragTarget` in `finally` **inside the acquired mutation block**.
- Targeted `anchoredDrag`: move its existing cleanup inside that same ownership interval. The rejected-operation regression proves that the old outer cleanup can clear another owner's marker.
- Preserve public API, successful settlement, confirmation, anchors, touch slop, velocity, fling, animation and gesture interruption. Cancellation/exception cleanup only retires the marker; it does not settle, snap, start animation or suppress cancellation.

Pinned base inputs:

| Input | SHA-256 |
| --- | --- |
| Published Foundation Android1.12.0 AAR |657c05465eddae8ff7a1e55a4a615bc26da9012834a44e1e4282deeb54ae8ea7 |
| Published1.12.0 sources JAR |5b58cea5db2583142e4b4b39dce10cbd75722bd06ab35161f06f987be90a53f1 |
| Original classes JAR |e8a5f059e7ada7b60209d6fc8cb79283e9af989b3daadf1aa13f7d9a2b8fe31f |

## Conditional producer qualification

Main will implement a reproducible source-backed producer, not edit Gradle caches or hand-edit coroutine bytecode. Pin compiler/Compose-plugin versions, all compile/friend classpaths and options, Java11 bytecode target and `foundation` module name. Use the exact original classes as a friend classpath to compile the one original source file.

**Gate A:** compile unmodified source first. Identify the complete source-generated class family using source attribution/enclosing relationships/references, not filename prefixes. Require the same class set and normalized class compatibility with the published artifact, including Kotlin metadata, internal/synthetic/default members and Compose signatures. Only line/local debug tables and debug line-number annotations may be normalized. The unchanged invariant tests must still fail on the repackaged unpatched control. Any unexplained compiler or linkage delta rejects this producer.

**Gate B:** compile the approved patch using the same inputs. Only the mutation-lifecycle coroutine implementations and necessary spill/exception state may differ. All public/protected/restricted/internal/synthetic linkage must remain compatible; unchanged classes must resolve against the replacements. Preserve original complete Kotlin module mapping and all unrelated AAR bytes/resources/proguard/baseline profiles. No duplicate, obsolete or missing generated class. Record complete input/patch/replacement-class/output manifests and hashes, and demonstrate repeatable production.

If qualification fails, stop and report. **A full AndroidX build, arbitrary compiler changes, ASM state-machine edits, class shadowing or wider source changes are not authorised by this proposal.** Those would require a separate concrete decision.

After both gates pass, use an identifiable patched coordinate (`androidx.compose.foundation:foundation-android:1.12.0-element-anchor.1`, with the corresponding multiplatform metadata redirect). Deterministic substitution must cover debug/release/tests/lint and retain transitive/variant metadata. A guard verifies the base version, patch/provenance/output hashes and exactly one intended Foundation implementation in resolution and the installed APK. No silent original coordinate/cache replacement. Bootstrap recipe/documentation supplies the artifact locally and in CI; binaries are not committed or externally published.

## Verification and resource envelope

Keep all existing regression assertions unchanged. Require green normal completion/cancellation/exception/ownership handover/rejected-acquisition tests, both wrapper cases without inset padding, genuine animation interruption, messages/designsystem/mediaviewer tests and both app flavours. Independently run native expansion→stationary Edit caption→one replacement→unchanged item/source digests→edited persistence, plus single-media actions, direct and nested dragging, partial/expanded transitions, dismissal and Back. Verify patched classes in the actual installed APK. Remove every temporary reflection/pointer/coroutine diagnostic before clean final acceptance; finish original full gates/J1–J13, review and merge.

Proposed new `android-foundation-private` admission: **4 CPU /12 GiB /4 h**, an estimate, not a measured peak. At most two compiler/backend threads with2GiB heap; then at most two Gradle workers with3GiB debug/1GiB Kotlin heap; then one1536MiB/two-CPU emulator. Compiler/control/patched production, tests, Gradle and emulator stages are serial, with existing locks before admission and owned cleanup before release. No global pool change or estimate reduction.

Rebuildable compiler/dependency/class/m2 outputs go under `${XDG_CACHE_HOME:-$HOME/.cache}/dev-scratch/element-x-android/<workspace-name>/foundation-private/`. Keep the source patch, recipe and useful qualification/provenance reports durable. Retain recoverable baseline artifacts and active environments; teardown waits for owned processes before releasing admission. No cache cleanup without ownership/inactivity checks and copying useful evidence out.

Remove the backport only when a released stock Foundation contains an identifiable ownership fix and passes the same unchanged regressions plus native verification. Upstream issue/patch material may be drafted locally; this approval does not authorise sending messages or publishing upstream.
