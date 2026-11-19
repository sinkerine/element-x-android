# Foundation anchor cleanup qualification

The approved backport is conditional on `docs/features/mixed-file-gallery-foundation-amendment.md`.
Normal project builds select the identifiable local coordinate
`androidx.compose.foundation:foundation-android:1.12.0-element-anchor.1` after the bootstrap below.
The source-family control reproduced all 34 published classes with Kotlin/Compose 2.3.20,
language/API 2.1, Java 11 and one backend thread.
The unmodified expect/Android actual cancellation superclass sources provide same-module stability context;
their output classes and the compiler's partial Kotlin module mapping must never replace published entries.

## Normal builds and CI

Run `nix develop --command ./tools/gradle-local.sh --bootstrap-foundation` before normal Gradle configuration.
The launcher retains the shared build guard while the bootstrap's direct Gradle descendants run serially.
Independent verification instead retains both existing external guards and the `android-foundation-private`
4 CPU/12 GiB/four-hour admission, then runs `python3 tools/foundation/bootstrap` directly; do not reacquire
its already-held shared guard through the launcher. All compiler invocations use 2 GiB/one backend thread.

The bootstrap recompiles the unmodified source and requires the same complete 34-class normalized family,
compiles the approved patch twice, checks the exact linkage/lifecycle scope, preserves unrelated original
bytes and module mapping, and requires the COMPLETE AAR to equal the already independently qualified
SHA-256 `6845962e3b95e1919b94f8f0a0cb8eb5acc18df3817fd9c24aaa72c19d5b15d8`.
This is reproduction of a fixed qualified binary, not new qualification or fabricated control-RED tests.
A different binary stops before publication. Initial qualification retains the original control-RED procedure below.

`--ensure` reuses only an exact artifact/publication/source-recipe closure. Publication stays in owned scratch,
with hash-pinned upstream POM licences/attribution/dependencies and complete MPP/Android variant metadata.
Nothing is committed to binary storage or published externally. Normal settings fail closed with the bootstrap
instruction when provenance is absent or stale. They check the base/patch/output and publication/recipe hashes,
canonicalize both root and direct Android requests to one patched Android component, guard every resolved
configuration, and check every one of the 1,981 classes in every attributed AGP Test carrier without filtering
or deduplicating the classpath. The pinned Compose BOM also prevents silent upgrades around this backport.

CI's `.github/actions/setup-gradle-with-foundation` retains the existing pinned Gradle setup action and cache
policy, then runs `python3 tools/foundation/bootstrap --ensure`. It needs JDK 21, Python 3.11+ and Android SDK
platform 37, as do the existing Android jobs. It does not require the private SDK to reproduce Foundation;
normal app configuration still requires the separately documented private Rust SDK prerequisite.
Use `:app:verifyFoundationBackport` to inspect normal debug AND release runtime selection for both flavours;
no qualification init script is used for final app/unit/lint builds. Binaries remain under the scratch path below,
while ignored `build/foundation-private/production-repository.json` records complete local provenance.

Remove the backport only after a released stock version contains the ownership repair AND passes the same
unchanged lifecycle/wrapper regressions and real native interactions. Update the BOM/version guard deliberately,
remove the bootstrap/settings/CI indirection together, and retain qualification evidence. Never silently map
back to stock or replace a remote/cache coordinate with local bytes.

## Initial qualification and diagnosis

Run `nix develop --command python3 tools/foundation/qualify-repacked-control` only inside the approved
`android-foundation-private` admission, after the caller acquires the existing build guards. The recipe
uses direct Gradle commands under those guards; do not wrap it in `tools/gradle-local.sh` and reacquire
the shared lock. Retain the lock in the launcher parent and close its descriptor in the qex client child.

The recipe first recompiles the unmodified source, checks source/enclosing/nest closure and exact class
traces (only ASM `SKIP_DEBUG`), then repacks only the qualified class family. It preserves every unrelated
class, the complete original Kotlin module mapping and all other AAR entry contents. The isolated control
Maven repository retains the original variant attributes, dependencies and constraints; its distinct
`io.element.android.foundation.control` group is consumed only through an explicit init script invocation.
The init script requires exactly one resolved Foundation implementation component. AGP adds both
`android-classes-jar` and `android-java-res` to unit-test classpaths; each physical Foundation carrier
must match an artifact view of that same component. Every class definition in every carrier must match
the qualified control AAR, including all 34 replacement-family entries. Unknown carriers, directory
shadows, differing definitions and duplicate class entries are rejected. No Test classpath entry is removed.
These explicit qualification invocations retain isolated coordinates; normal builds follow the production guard above.

The original three state regressions and both wrapper regressions must remain assertion failures on this
unpatched control. Infrastructure failures, missing tests, skips or changed failure counts reject the run.
The recipe forces fresh test execution and records logs, XML and exact exit codes under
`build/foundation-private/`. Patched production, compatibility/linkage/repeatability and native acceptance
are subsequent gates; control comparison or expected red tests alone do not qualify a patched artifact.

After independently accepted control proof, `nix develop --command python3 tools/foundation/compile-candidate`
checks the current control inputs/runtime identity/XML prerequisites, applies only the approved patch in a
fresh scratch source tree, and compiles twice with the measured control command. It retains every raw
normalized difference and checks repeated compiler output equality. It does not assemble a patched AAR,
install dependencies, normalize away changes or declare Gate B compatibility. Run it inside the same
resource admission/guard envelope; all prerequisites must remain unchanged through the attempt.

`check-candidate-contract` inspects the three lifecycle classes with ASM. It requires identical complete
member declarations/flags/annotations/Kotlin metadata (only debug source positions are excluded from
comparison), exact code equality outside the targeted overload and two mutation `invokeSuspend` bodies,
and no newly introduced symbolic references. The old outer continuation must have no references in any
retained original entry or candidate entry. Its negative control must reject the unpatched archive.
Packaging will retain every other ORIGINAL class byte; unrelated compiler source-position rewrites are
not included in the replacement payload. This check still creates no AAR or dependency installation.

`qualify-candidate-tests` next assembles the three whole-class replacements, removes the unused outer
continuation, and preserves every other original AAR/class/module entry. Two independent compiler outputs
must produce byte-identical complete AARs. Mutable accepted-control build reports/XML are snapshotted
before test output paths are reused. `publish-control --candidate` creates an isolated candidate test
repository; the normal project settings and dependencies remain unchanged. The same provenance guard
verifies one component and every one of its 1,981 class definitions, including the complete 33-class
source family, in every AGP form. Both unchanged regression suites must pass with zero skips/errors.
Candidate tests do not establish native touch, installed-APK identity or full feature acceptance.

`verify-lifecycle-coverage` runs the original state cases and expanded exception/cancellation/ownership/
real-frame animation-interruption cases against the accepted isolated AAR. `verify-candidate-consumers`
then verifies both app runtime variants resolve that exact AAR, runs the affected consumer modules,
compiles Gplay and Fdroid debug consumers, and builds the x86_64 Gplay debug APK. These explicit
qualification invocations still leave normal project dependencies unchanged. Native journey and actual
installed-binary identity are subsequent checks; temporary debug diagnostics must be removed for final acceptance.
The isolated publisher retains hash-pinned upstream POM licensing, attribution, SCM and developer metadata,
in addition to the complete Gradle variant metadata. It does not bypass application licence validation.
When module tests have already completed and an app-only boundary needs repair, `verify-candidate-consumers
--app-only` binds and reuses their unchanged reports, refreshes local metadata, and runs only the variant,
app compilation and APK stages; it does not count those historical unit cases as freshly rerun.

All rebuildable compiler inputs, source extraction, class outputs, AARs and local Maven repositories use
`${XDG_CACHE_HOME:-$HOME/.cache}/dev-scratch/element-x-android/<workspace-name>/foundation-private/`.
Setup and teardown use this same directory. Keep useful qualification evidence in durable run reports;
retain baseline inputs and active outputs. Before deleting any scratch, verify ownership and that no
compiler, Gradle process, test or emulator uses it. Do not delete shared Gradle caches or active runs.
