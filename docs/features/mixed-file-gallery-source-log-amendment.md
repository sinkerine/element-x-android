# SDK media-source log repair

Status: **Approved by the user: “Approve supplemental log patch”; recorded 2026-11-17 11:30 PST (UTC−08:00) on kazuki.** Existing feature, image-integrity, screenshot and two-filename-field SDK approvals remain unchanged.

The independent SDK-private r4 run successfully built/validated/installed the private SDK and compiled both Android flavours. Real upload/navigation logs contain no selected filenames or captions. The same bounded logs contain twelve SDK TRACE lines with media source URIs and encrypted-file debug metadata. The encrypted debug representation hides key/IV; no key leak is asserted. The existing SDK amendment explicitly requires source absence, so that criterion remains failing.

The existing SDK amendment permits only removing the filename fields in two DEBUG messages. This proposal expands that narrow source scope to remove source-valued fields from six adjacent logging statements in the same upload/cache path:

| Source | Change |
| --- | --- |
| `crates/matrix-sdk/src/send_queue/mod.rs` | Remove `mxc_uri` from successful-upload TRACE, retain transaction relation and message; remove the now-unused URI binding used only by this log. |
| `crates/matrix-sdk/src/send_queue/upload.rs` | Remove `from`/`to` source representations from the media-file cache TRACE and both thumbnail cache TRACE variants. Keep dimensions and messages. |
| Same thumbnail cache routine | Remove the same source field from the missing-thumbnail WARN and cache-read ERROR; retain severity, message and error diagnostic. These adjacent branches expose the same source value. |

The concrete supplemental patch is [gallery-source-log-privacy.patch](../../tools/sdk/gallery-source-log-privacy.patch), SHA-256 `95ded72c6b2a5f2c05608457345ef9a246f9ae93d76fb6dc17d00534264e95e3`. It is formatted with the SDK CI-pinned formatter and passes `git apply --check` against the exact isolated SDK source with the already-approved filename patch. No source, native binary or installed SDK was changed while preparing this proposal.

This changes logging fields only. SDK APIs, event bodies, media descriptors, cache operations, encryption, UI controls, logger verbosity and feature defaults remain unchanged. The original two-field patch is retained. No screenshot exception or acceptance criterion is relaxed.

On approval, main will integrate the supplement into deterministic isolated SDK generation, exact tree/hash provenance and the Android dependency guard. The independent verifier will rebuild both ABIs, run full formatting/scoped lint and existing send-queue regressions, preserve ABI/API inventories, validate both Android flavours and inspect bounded logs during real synthetic upload/navigation and restart. The original SDK checkout stays read-only; previous artifacts and raw failed evidence remain local.

Use the already-approved `android-sdk-private` **4 CPU /12 GiB /4 h** declaration with the same serialized workers/heaps/emulator bounds. No additional resource variant, lower estimate, parallel build, publication or credential access is requested. Native touch diagnosis and final all-gates/journeys/review/merge remain required independently.
