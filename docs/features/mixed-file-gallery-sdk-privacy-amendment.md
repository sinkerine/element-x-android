# Proposed SDK upload-log privacy repair

Status: Approved by the user; recorded 2026-11-17 00:44 -08:00 on kazuki. This changes only the original plan's D10 no-SDK-change boundary and adds a named resource declaration for the extra native build workload. All gallery behavior, existing API compatibility, quality, screenshots, review and delivery requirements remain intact.

## Actual finding and prepared patch

The independent diagnostic run found native DEBUG upload messages containing the synthetic original filenames. The SDK source at commit `96d0135495d68dd76a80510a41a86e2f90270490`, `crates/matrix-sdk/src/send_queue/upload.rs`, explicitly includes the `filename` tracing field in both single-attachment and gallery-attachment upload messages. This also exists on the approved base; it is not attributed to the temporary trace. Separate Appyx navigation payload logging is being repaired in the Android feature module, with three real-route red/green tests.

The concrete proposed patch is `tools/sdk/gallery-log-privacy.patch`: remove only the filename field from those two DEBUG messages. Retain MIME type, transaction IDs, message severity and all useful non-content diagnostics. Do not lower logging verbosity, change message content, remove user controls or alter sending. The patch passes `git apply --check` against the owning exact SDK source. It has not been applied, and no SDK compilation or external source mutation has occurred.

## Proposed scope D13

- Create an isolated SDK source copy inside this feature worktree's ignored build area from the exact existing SDK revision, apply the two-field patch there, and retain the original external SDK checkout unchanged.
- Keep the patch and a reproducible build recipe/provenance in this Android repository. Do not push, publish, deploy or commit to an external repository.
- Build patched native libraries for both x86_64 and arm64-v8a sequentially and package them with compatible Kotlin/UniFFI bindings into this worktree's ignored local AAR. Preserve the old verified AAR and atomically replace only after validation.
- Verify retained Threads Inbox, Hermes, gallery and existing API/native symbols, both ABI libraries and consumer app graphs. No new API or protocol/backend work.
- Run appropriate existing send-queue SDK regressions and the original Android verification inventory. Independently check actual logs during synthetic upload/navigation and after restart; filenames, captions, keys and sources must not appear. A quieter logging configuration is not substituted for removing the sensitive fields.
- Main session writes source, tests and build tooling. The independent verifier executes queued build/E2E proof. Existing approval and review-round records remain valid; final acceptance still requires all relevant checks and real touch behavior to pass.

## Proposed qex cost

New named variant: `android-sdk-private`, covering isolated SDK dependency preparation, existing SDK regressions, two native ABI builds, Kotlin AAR packaging, Android debug build/checks, synthetic emulator proof and complete cleanup. Stages are serialized: no Rust/Gradle/emulator overlap. Cargo jobs2, Gradle workers2 with 3 GiB debug heap and 1 GiB Kotlin heap, then one 1536 MiB/two-CPU emulator.

```qex-e2e android-sdk-private
{
  "resources": {
    "cpu": 4,
    "mem": "12GiB"
  },
  "timeout": "4h"
}
```

This reuses the user's chosen CPU/RAM envelope while allowing a longer bounded cold native-build window. It is a conservative proposed claim with explicitly bounded workers, not a measured peak. Current host reports roughly33 GiB available RAM; the proposed12 GiB plus the previously observed8 GiB reserve fits. Shared scheduling still controls admission and may queue work; no shared-budget change or admission bypass is proposed. Rebuildable Cargo/Gradle outputs and downloaded SDK dependencies use project/workspace-specific dev-scratch caches; source patch, credentials, provenance and useful reports remain durable. No workload starts and no new declaration is finalized until the user chooses its scope/cost.

## Approval

Reviewed proposal SHA-256: `121cbe03b8f0a3652f13e99aebc935ec453d789740a46888ae26e128ef72f0d1`. User explicitly answered `Approve patch and 4CPU / 12GiB / 4h` through ask_user. Only this approval record and Status line changed after the reviewed proposal.
