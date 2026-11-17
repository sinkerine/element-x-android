# Mixed-file gallery screenshot acceptance amendment

Status: Approved by the user (2026-11-16, kazuki system timezone). Original plan: mixed-file-gallery-plan.md. The approved image-integrity amendment remains unchanged.

## Executed evidence

Independent verifier ran screenshot verification on the exact approved base `0b30b979f4751ff0594544c1bce287089967e59d` and the feature using the same toolchain, 4 CPU/12 GiB admission, 3 GiB Gradle/1 GiB Kotlin heaps and identical checked-in golden bytes. Base: 3390 cases, 307 failures. Feature: 3398 cases, 327 failures.

The exact comparison is preserved at `build/e2e/mixed-file-gallery/repair-proof-r1-20261117T062740Z/G9-exact-comparison.json`, with raw XML and actual rendered PNG hashes. All 307 common failures have byte-identical actual base/feature rendered PNGs. There are no base-only failures. The 20 existing gallery and four attachment-list cases pass on both sides.

The remaining feature failures comprise six already approved new mixed-gallery expectations, two newly introduced gallery-file viewer expectations, and twelve existing snapshots that pass on base but fail on the feature. Those twelve are not covered by this proposal.

## Proposed replacement for G9's pass criterion

1. Run the complete screenshot discovery. Do not exclude tests, reduce thresholds, record goldens locally or change existing golden files.
2. Permit only the exact 307 pre-existing failure names in the comparison's `same_failure_rendering` list, and only when each final feature rendered PNG is byte-identical to its independently executed approved-base rendered PNG. A changed render, new failure name, missing comparison or incomplete discovery fails.
3. Retain the original allowance for missing new `TimelineItemGalleryView_{Day,Night}_{10,11,12}_en` goldens.
4. Also permit missing goldens for only `libraries.mediaviewer.impl.viewer_MediaViewerView_25_en.png` and `libraries.mediaviewer.impl.viewer_MediaViewerViewLandscape_25_en.png`. These append the new gallery-file filename heading after the 25 old viewer states; CI records them when requested through the existing repository process.
5. Every other discovered screenshot must pass against its existing golden. In particular, all twelve currently feature-only existing failures must be repaired or independently resolved without weakening this criterion. Existing gallery, attachment-list, viewer states0–24, and navigation golden files remain unchanged.
6. Preserve raw nonzero reports and classify the approved exceptions explicitly in the verification deliverable. An adjudicated G9 pass is conditional on this exact rule; never report that raw screenshot verification had zero failures.

All other acceptance criteria, unit/consumer/quality gates, native touch caption editing, full E2E, independent review and final merge requirements remain unchanged. This is a screenshot acceptance change only; it does not approve broken user behavior, new image differences or baseline replacement.

## Approval

Reviewed proposal SHA-256: `5a8d236a54f15d637936848cffd7056e049f646cfcab09c014de05fc40874509`. User explicitly answered `Approve the amendment` through ask_user; recorded 2026-11-16 23:20 -08:00 on kazuki. Only this approval record and Status line changed after the reviewed proposal.
