# Mixed-file gallery integrity amendment

Status: Approved by the user (2026-11-16). Original approved plan: [mixed-file-gallery-plan.md](mixed-file-gallery-plan.md).

## Reason

Independent C0 on unchanged base `0b30b979f4`, tooling checkpoint `d88efa8757`, proved DocumentsUI multi-select, preview, exactly one encrypted gallery event, expected item order/type/name/MIME/size and successful second-client decryption. PDF plaintext SHA-256 equals the original. PNG plaintext SHA-256 differs identically in two sends although the device input matches the fixture manifest. C0's original exact-byte assertion failed and remains recorded as failed.

`AndroidMediaPreProcessor.processImageWithoutCompression()` invokes `removeSensitiveImageMetadata()` even for Files-picker images. The latter strips GPS, comments and other sensitive metadata through ExifInterface. This pre-existing behavior conflicts with the original plan's universal original-byte equality requirement; the precise cause of the observed PNG rewrite still needs proof. No product changes have been made to this processing.

## Proposed decision D12

Preserve existing image metadata sanitization and all existing compression/quality rules. Do not change AndroidMediaPreProcessor, bypass privacy processing, or claim the existing PNG hash mismatch was a pass. Amend image integrity evidence as below; every other approved requirement remains in force.

## Exact acceptance changes

- **AC2, AC6 and their C0/J2/J5/J6/J10 projections:** non-image fixture files (PDF, WAV, text, extensionless binary) must retain exact original plaintext SHA-256. Files-picker PNG fixtures must retain their original decoded pixel dimensions and every RGB pixel in order after independent download/decryption. Their encoded-file SHA-256 is recorded for provenance, but equality with the input PNG encoding is not required. Names, MIME types, item order, gallery count, captions, mentions, source encryption and complete encrypted descriptors retain the original requirements. Existing image size reporting behavior is not changed by this amendment.
- **C0:** the old run remains a failed original-byte experiment. Before releasing T2, independently prove image pixel/dimension equality on the actual received PNG alongside the other unchanged C0 checks. If the transmitted plaintext is not available from the completed run, repeat the necessary real picker send through the independent verifier under fresh admission. Successful observation of a different SHA-256 alone never proves pixel equality.
- **AC11/schema:** keep the original field/schema requirements. Add independent decoded-image observations; no altered transport or encryption contract.
- **AC12/D6:** image processing, metadata removal, image/video quality rules and Files-picker sendAsFile behavior stay unchanged. No new metadata/compression feature or option.
- **T1 evidence tooling:** extend the real device-fixtures manifest and gallery-last projection with PNG width, height and SHA-256 of decoded canonical RGB pixels for fixture image comparisons. Compute the received-image observation from the authenticated downloaded and decrypted bytes, not from the expected fixture name/hash. Add red-then-green regression tests that accept a differently encoded PNG with identical pixels and reject changed pixels/dimensions. Keep existing encoded-file SHA-256 fields and strict non-image assertions. Fail closed for unsupported/malformed image evidence; do not substitute expected values. T1 owner prepares tooling; independent verifier executes the real C0/Journey checks.
- **DOC2:** report raw encoded hashes and decoded fixture image comparisons separately, preserve both original failed C0 sends, and identify this approval as the only change to their integrity criterion.

## Approval

Reviewed revision SHA-256: `7cb80d5a815627dec5939ecb712274cdef401079015ffdd0c2085330810cf7e7` (before this editorial approval record/status update). User explicitly answered `Approve amendment; preserve metadata stripping` through ask_user on 2026-11-16 (recorded at 17:39 -08:00, kazuki). Approval authorizes exactly the acceptance/evidence changes and T1 evidence-tooling repair above. It does not approve changing image privacy behavior, waive the capability gate, or claim a final E2E result.
