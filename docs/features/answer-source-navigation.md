# Answer and source navigation

The default Android timeline renderer offers **In this message** for formatted text and notice messages with at least three nonempty top-level headings and 2,000 rendered characters of eligible content. The feature is enabled by default. It applies to individual Matrix events in room and thread timelines.

The entry opens a native bottom sheet with numbered sections. Selecting a section dismisses the sheet and brings its heading and passage into view. The explicit jump reserves most of the current timeline viewport below the heading so a forward jump cannot leave the passage off-screen. Repeated names have independent positions. **Sections** beside each heading reopens the index from that passage and marks the selected section as **Current**. Dismissing the sheet does not request a scroll.

The sheet includes **Sources** only for HTTP(S) links within explicit **Sources** or **References** headings (English, case insensitive). Sections end at the next heading of the same or higher level; deeper subsections remain included. Duplicate destinations retain their first author-supplied label. Each row displays the label and destination host. Ordinary links elsewhere are not classified as supporting sources. No metadata is fetched or attribution inferred.

Selecting a source dismisses the sheet and forwards the original URL and label to the existing Android link handler. This preserves existing link confirmation, permalink handling and external-app behavior. Inline links continue through the same handler. Returning from a source reveals the same passage. The dedicated room, thread and narrow-viewport journeys verified unchanged heading bounds after returning from Chrome.

Structure is read from the original formatted body because the existing sanitizer removes heading tags. Every displayed fragment still passes through the existing sanitizer, maths conversion, mention processing and rich-text span converter. The stored body and sanitized document used for copying, editing and replies remain unchanged. The navigation uses local view targets, never author-supplied heading IDs. Edits recreate the navigation and its targets, including removing the control when the answer becomes ineligible.

Quoted content, reply fallbacks, code and spoilers do not supply navigation entries or sources. A heading or source label containing excluded content is omitted in full. Quoted and hidden prose cannot make a short answer eligible. Nested headings inside list items or other containers remain content rather than independent navigation targets.

Search results use their existing snippets. Emotes and media captions do not acquire this control. Interactive cards keep their existing renderer; text fallbacks use their normal timeline text path. The alternate Compose timeline renderer remains disabled by default and is outside this initial navigation integration. General streaming and timeline scroll anchoring belong to the separate stable-answer-reading workstream.

This feature concerns ordinary visual reading and navigation. It does not add reader modes, document editing, answer grouping, sensory-accessibility work or voice features.

## Verification

`AnswerNavigationParserTest` covers eligibility, repeated headings, exclusions, explicit sources, nested source boundaries, safe source schemes, duplicates, edits and sanitization. `TimelineItemContentMessageFactoryTest` covers default-enabled integration for text and notices and preservation of converted link spans. `AnswerNavigationViewTest` covers numbered selection, heading re-entry, the current marker and source callbacks. Day/night previews cover answers and indexes with and without sources.

## Local verification result

In the assigned `feat/answer-source-navigation` worktree, final validation passed: the complete messages module suite ran **744 tests, zero failures/errors, one skipped**; formatting and `detekt` passed; the Gplay debug APK assembled successfully. The new coverage includes 16 parser tests, four Compose interaction tests and two factory integration tests. The parser uses the repository's `tryOrNull` helper for malformed URLs.

The authenticated emulator blocker was repaired using the supported **`-gpu swangle -feature -Vulkan`** configuration, preserving the dedicated AVD's existing session. Direct SwiftShader GLES had crashed the host QEMU RenderThread with SIGSEGV (PID 320424). The shared sandbox launcher now uses the working graphics configuration. No app state was cleared. The local fixture launcher was also corrected to avoid appending duplicate Synapse configuration on restart, and the inspection helper rejects stale hierarchy files after a failed UI dump.

Runtime testing exposed an actual forward-navigation defect: requesting only the heading placed it at the bottom of the viewport, leaving its passage off-screen. The explicit section jump now requests a rectangle containing the heading and space for its passage, using the current timeline viewport. The repeatable journey navigates backward to section 1 and forward to section 4, so it detects this defect.

Verification used the dedicated `answer_navigation_a1` AVD, **API 33 / x86_64**, Pixel 2 profile, 2 GiB guest RAM and two cores, serial `emulator-5598`. Each changed APK was installed once using `adb install -r --no-incremental`; package timestamps confirmed an in-place update. Dedicated synthetic accounts and an unencrypted room used a loopback-only Synapse at port 18038. The default rich-text renderer was used throughout.

All four runs of [the standalone Maestro journey](../../.maestro/tests/answerNavigation/answerNavigationJourney.yaml) passed, with 23 completed checks/actions per run:

- Room text, including navigation in an edited answer.
- A notice message in the room.
- The answer inside a thread.
- A **320dp-wide** viewport (840 pixels at density 420), including browser opening and return.

The runs cover numbered sections and duplicate headings, quoted-heading exclusion, author-supplied source label and host, backward/forward section navigation, heading-to-index re-entry, the current marker, dismissal, browser URL and return. Additional interactive assertions verified the initial **In this message** entry, ordinary-link-only answers having no Sources area, the original short message having no navigation, and a live edit from a long answer to a short reply removing navigation.

Independent coordinate assertions checked that the second Evidence heading remained at **`[116,398][348,466]`** before opening the sheet, after dismissing it, and after opening the source in Chrome and returning. This passed separately in the room, thread and 320dp viewport. The fixture server's versions response was visibly loaded in Chrome; browser opening alone was not treated as sufficient evidence.

The display size was restored, adb reverse removed, the owned Synapse container stopped, and only the dedicated AVD shut down. Test data and session were preserved. The shared common-Git-directory lock was released and the other Android feature agent notified.

Evidence remains local under `build/e2e/answer-navigation/`: `passage-fix-build-2.log`; `room-journey-fixed`, `narrow-journey`, `thread-journey` and `notice-journey` logs/Maestro directories; `room-fixed-result.json`, `thread-fixed-result.json`, `narrow-fixed-result.json`; `initial-entry.log`; source-free and short-edit hierarchy dumps; screenshots; and `emulator-swangle-r2.log`. Credentials and raw artifacts must remain local. Before archiving the feature workspace, the complete local evidence, unit-test XML results, verified APK checksum, synthetic fixture state and dedicated AVD were preserved at `.git/e2e-evidence/answer-source-navigation/` in the repository's common Git directory. The copied AVD registration points to its preserved location. These are synthetic unencrypted Matrix fixtures, not a live Hermes or encrypted-room acceptance run.

Stable-answer-reading integration is included in the combined develop implementation. Every `AnswerFragment` forwards a distinct stable reading key through `TimelineItemTextView`: `answer:introduction`, `answer:section:0:heading`, `answer:section:0:body`, and corresponding slots for each section ordinal. Keys never depend on heading text, rendered text or a generated value. Native `onPlaced` coordinates and `onTextLayout` are forwarded, and the caption retains its separate `caption` slot. Stable slots prevent `ContentAvoidingLayout` from retaining outgoing, still-attached TextViews during subcomposition replacement. The viewport callback and forward-jump passage rectangle are preserved.

Combined verification passes 767 messages implementation tests with zero failures/errors and one existing skip, plus Android lint, detekt and the debug build. Native room/thread tests cover growth before and after a reading fragment, later events, and editing a structured message back to a short body. The existing 23-action/check Maestro navigation journey passes on the combined APK in both a room and a thread. Further Matrix edits grow earlier/later sections and send chunks from two participants while the selected passage stays at exactly 0px movement in four screenshot-template comparisons. See [the stable-reading record](stable-answer-reading.md) and common-Git-directory evidence at `.git/e2e-evidence/stable-answer-reading/integration/`. No Hermes, Web or SDK source changes are required.

For future regression runs, follow [the fixture and flow instructions](../../.maestro/tests/answerNavigation/README.md) and repeat the room, notice, thread and narrow-viewport journeys. Screenshots and raw device evidence belong under ignored `build/e2e/answer-navigation/`; do not record screenshot-test baselines locally.
