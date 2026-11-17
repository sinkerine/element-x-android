# Mixed-file gallery: plan

Status: **Approved by the user (2026-11-16).**

The later user-approved [image-integrity amendment](mixed-file-gallery-integrity-amendment.md) governs the original-byte assertions for image fixtures in AC2/AC6/AC11/AC12, C0 and J2/J5/J6/J10. The later user-approved [screenshot acceptance amendment](mixed-file-gallery-screenshot-amendment.md) governs G9 and AC12 screenshot acceptance. The original wording below is retained as approval history; all other requirements remain in force.

Feature slug `mixed-file-gallery`, branch `curly-seal`, worktree `/home/sinkerine/.paseo/worktrees/3b36k2jo/curly-seal`
(Paseo workspace `wks_2b8adb01b9517d57`), base `refs/remotes/origin/develop` at `0b30b979f4`. Run state:
`/home/sinkerine/dev/element-x-android/.git/worktrees/curly-seal/e2e-feature/mixed-file-gallery` (`$RUN`).

User request: "support the mixed multi file/media gallery support that done in element-web repo".

Reference: Element Web `/home/sinkerine/dev/element-web` at `a43a3faee4570a894226ce64b80a908636057722`. The relevant
files are `docs/features/mixed-file-gallery-plan.md` and the T1 commit `2b7b3d3548`. The Web change sends any mix of
images, videos, audio and other files as one MSC4274 `dm.filament.gallery` event with a shared caption. It renders visual
items in a grid and audio/file items as rows below it. The Web code was read only. Native Android behaviour and
conventions take precedence over Web components.

## 1. Goal and acceptance criteria

**Goal.** A user can pick any mix of images, videos, audio and other files from the composer's Files picker. This
works in the main timeline and in threads, in encrypted and plaintext rooms. The user reviews the files in the existing
preview carousel and sends them as **one** gallery message with one optional caption. Sharing 2–60 files into Element X
from another app also sends one gallery per selected room. Received mixed galleries keep the image/video grid and list
audio and file items as rows below it. Each item opens in the media viewer with its real type. No Labs setting is
involved.

### What exists today (verified at `0b30b979f4`)

| Piece | Current behaviour |
| --- | --- |
| `FeatureFlags.SendGalleryMessages` (`libraries/featureflag/api/.../FeatureFlags.kt:147`) | Labs flag, `defaultValue = { false }`. `LabsPresenter.kt:92,98,104` gives it a title, description and icon. |
| Composer pickers (`MessageComposerPresenter.kt:192-206, 309-325`) | With the flag **off** (default), Gallery opens the single visual picker and Files opens the single `GetContent` picker. With it on, they open `ImageAndVideoMulti` (max 60) and `FileMulti` (`OpenMultipleDocuments`, `*/*`, no limit). |
| `handlePickedMediaList` (`:685-706`) | 1 URI → single path. 2+ → `navigateToPreviewAttachments(attachments, inReplyTo)` in pick order. Files picker items have `sendAsFile = true`, which only affects compression. Each item's msgtype still follows its MIME type. |
| `AttachmentsPreviewPresenter` (`:497-562`) | Pre-processes every attachment by MIME type (`AndroidMediaPreProcessor.process`: image/video/audio/any file). It sends 1 item with `sendPreProcessedMedia` and 2+ items with `mediaSender.sendGallery(infos, caption, …)`. Failure → `SendActionState.Failure` with retry. |
| Preview carousel (`AttachmentsPreviewView.kt:287-345`) | A `HorizontalPager` renders each item with `LocalMediaView`: image, video, PDF, plain text/JSON, audio player, or generic file. The pill reads `Item n of N`. |
| Media → gallery mapping (`MediaUploadInfo.kt:38-58`) | `Image`→`GalleryItemInfo.Image`, `Video`→`Video`, `Audio`/`VoiceMessage`→`Audio`, `AnyFile`→`MediaFile`. `RustTimeline.sendGallery` (`:567-595`) maps these to FFI `GalleryItemInfo`. |
| Timeline mapping (`TimelineItemContentMessageFactory.kt:312-455`) | **Every** item visual **and** some thumbnail → `TimelineItemGalleryContent` (grid). Anything else → `TimelineItemAttachmentsContent`, a flat list where images are 36 dp thumbnails. |
| Viewer (`MessagesFlowNode.kt:828-911`) | Gallery content passes real item types. Attachments content maps **every** item to `GalleryItemData.Type.File`, so images, video and audio in a mixed gallery open as generic file pages. |
| Share (`SharePresenter.kt:89-135`) | `ShareIntentData.Uris` sends each file as its own event per room, with the shared text repeated as the caption on **every** file. |
| Text surfaces | Room list `Gallery: <body>` (`DefaultRoomLatestEventFormatter.kt:171`); notification `messageType.body` (`DefaultNotifiableEventResolver.kt:414`); summary `common_gallery` or attachment extensions (`DefaultMessageSummaryFormatter.kt:61-69`); reply "N attachments" for non-visual mixes (`InReplyToMetadata.kt:111-155`). |
| Media protection | `mustBeProtected()` is true for gallery and attachments content, which only diverts the bubble click. `TimelineItemGalleryView` and `TimelineItemAttachmentsListView` take no `hideMediaContent`, so gallery items are never blurred today. |

### Rust SDK and backend compatibility (investigated, no change)

- The AAR `org.matrix.rustcomponents:sdk-android:26.09.9-custom.1` already exposes FFI `GalleryItemInfo::{Image, Video,
  Audio, File}` (`~/dev/matrix-rust-sdk` `bindings/matrix-sdk-ffi/src/timeline/mod.rs:1793-1900`). The wrapper maps all
  four.
- `make_gallery_item_type` → `make_media_type!` (`crates/matrix-sdk/src/room/mod.rs:306-390`) picks each item's
  msgtype from its MIME type: `image/*` → `m.image`, `audio/*` → `m.audio`, `video/*` → `m.video`, anything else →
  `m.file`. Without a per-item caption, `body` is the file name and `filename` is unset. The gallery caption is the
  top-level `body`/`formatted` (`send_queue/upload.rs:458-470`). This is the same shape Element Web emits and accepts:
  `itemtype`, `body` = name, `url` or E2EE `file`, `info.size`, `info.mimetype`.
- The send queue resolves uploaded sources for `Audio` and `File` items (`send_queue/upload.rs:143-150`).
- The SDK has no item cap (no `MAX_GALLERY` in `crates/matrix-sdk` or `bindings`). Android enforces MSC4274's 60 (D5).
- Per the Web plan's investigation, Synapse and Hermes need no change: event content is opaque to the homeserver, and
  Hermes `main` turns `m.file`/`m.audio` gallery items into one agent turn. This plan did not re-verify Hermes and makes
  no external edits.
- Receiving already parses all four item types (`EventMessageMapper.kt:126-185`).

### Decisions

| # | Question | Decision | Source |
| --- | --- | --- | --- |
| D1 | Enablement | **Remove `FeatureFlags.SendGalleryMessages` entirely.** The composer always registers and launches the multi pickers: Gallery → `ImageAndVideoMulti` (max 60), Files → `FileMulti` (`*/*`). The single-select `galleryMediaPicker` and `fileSinglePicker` registrations and the three `LabsPresenter` branches are deleted. The Labs strings stay in `localazy.xml` (never edited; lint ignores unused strings there). The stale persisted key is ignored. | User, 2026-11-16 |
| D2 | Timeline rendering of mixed galleries | **Grid plus file rows, as on Web.** A gallery with ≥1 image/video item, at least one of which has a thumbnail, becomes `TimelineItemGalleryContent` containing **all** items in event order. Images and videos fill the existing grid layouts, chosen by visual count, with `+N` counting visual items only. Audio and file items render as rows below the grid, styled like the existing attachment rows. Galleries with no visual item, or whose visual items have no thumbnail, stay `TimelineItemAttachmentsContent` (unchanged flat list). All-visual galleries map exactly as today. | User, 2026-11-16 |
| D3 | Viewer item types | Tapping any tile or row opens the existing gallery viewer at that item's index in event order (over all items). Every item keeps its real type: Image/Video/Audio/File pages. This includes `TimelineItemAttachmentsContent`, whose items currently all open as File. | Design (follows D2) |
| D4 | Sharing multiple files into Element X | **2–60 shared files → one gallery per selected room**, with the shared text as its single caption, in `EXTRA_STREAM` order. One file → the existing single `sendMedia` (unchanged). More than 60 → the existing per-file path (unchanged behaviour, so a large share still works). | User, 2026-11-16 (limit handling: design) |
| D5 | More than 60 files from the composer | `OpenMultipleDocuments` has no limit. When more than 60 URIs are returned, the composer opens no preview and posts a snackbar: `screen_room_attachments_limit` "You can send up to 60 items in one message." The composer draft and mode (reply/edit) are unchanged. Exactly 60 opens the preview. The constant `MAX_GALLERY_ITEMS = 60` lives in `libraries/mediaupload/api`. | Design (MSC4274 limit, matches Web D11) |
| D6 | Preview screen | No new UI. The existing carousel already renders every kind. The per-item image-quality/video-preset selectors appear only for images/videos, as today. Web's Add/Remove/Group controls are Web dialog features and are not ported. The `sendAsFile` semantics for Files-picker items (no image compression, best-fitting video preset) are unchanged. | Design (native conventions) |
| D7 | Text surfaces | Unchanged. A mixed gallery with a visual item now maps to gallery content, so its summary becomes `Gallery` (`common_gallery`), like image galleries. Room list (`Gallery: <caption>`), notifications, and reply metadata (computed from SDK items: "N attachments" with a file icon for non-visual mixes) are unchanged. Web D7's file-name fallback is not ported. | Design (preserve behaviour) |
| D8 | Media protection ("hide images and videos") | Unchanged. `mustBeProtected()` stays true for both content types, and gallery views still take no `hideMediaContent` (an existing gap, not introduced here). | Design (no speculative change) |
| D9 | Processing failures | Unchanged. If one item fails pre-processing, the existing `Failure` dialog and retry apply to the whole gallery. Web D5's per-item `m.file` fallback is not ported. | Design (preserve behaviour) |
| D10 | SDK/backend | No Rust SDK, AAR, homeserver or Hermes change. Nothing outside this worktree is edited or deployed. | Investigation |
| D11 | Flag/default | Enabled by default with no new flag (D1 removes the existing one). | Repository rule + D1 |

Required docs:

- **DOC1** `tools/interactive-ui/fixture-bot/README.md`: new section "Mixed-file gallery fixtures". It covers the new
  commands, the isolated state directory, the device fixture manifest, the evidence path, and cleanup (T1).
- **DOC2** `docs/features/mixed-file-gallery-verification.md`: written by the independent verifier. It records the
  tested commit, APK, device, every gate command result and every journey step outcome with its evidence path. It
  contains no secrets or message bodies beyond the synthetic captions listed here.

### Acceptance criteria

Device fixtures are generated by `node tools/interactive-ui/fixture-bot/bot.mjs device-fixtures --out <dir>` (T1). It
writes a `manifest.json` with name, MIME type, size and SHA-256 for each file:

- `red.png`, `green.png`: `solidPng(0)`, `solidPng(1)`;
- `report.pdf`: a one-page PDF;
- `notes.txt`: `"mixed-file-gallery notes\n"`;
- `tone.wav`: 1 s, 8 kHz, 16-bit mono sine;
- `noext`: bytes `[0,1,2,3,255]`;
- `bulk/bulk-01.txt` … `bulk-61.txt`.

**AC1 — No Labs flag; multi pickers by default.** `FeatureFlags.SendGalleryMessages` does not exist, and Labs shows no
"Gallery messages" entry. With default settings, Attach → Gallery opens the multi visual picker and Attach → Files opens
`OpenMultipleDocuments`.
_Evidence:_

- `git grep -n -e SendGalleryMessages -e send_gallery_messages -- '*.kt'` returns nothing.
- `MessageComposerPresenterTest`: `present - Pick media from gallery launches the multi picker by default` and
  `present - Pick files launches the multi picker and previews mixed files in pick order`. Both run with a
  `FakeFeatureFlagService()` that has no initial state.
- E2E J1 (Labs page shows no `Gallery messages`) and J2 (DocumentsUI multi-select with no Labs change).

_Pass when_ the grep is empty, both tests pass, and J1/J2 pass.

**AC2 — Mixed composer send produces one gallery event with every item.** For 2–60 files of any mix picked in the
composer, Send in the preview sends exactly one `m.room.message` with `msgtype: "dm.filament.gallery"`, items in pick
order. Each item has:

- `itemtype` from its MIME type (Rust SDK rule above);
- `body` = original file name;
- `info.size` = original size;
- `info.mimetype`;
- `file` (encrypted rooms) or `url` (plaintext);
- plaintext bytes equal to the original.

The caption is the top-level `body` (empty when none). It is sent once, with `m.mentions`.
_Evidence:_

- `AttachmentsPreviewPresenterTest`: `present - send mixed gallery sends one gallery with every item in order`. It
  drives the real `DefaultMediaSender` into `FakeTimeline.sendGalleryLambda`, with `FakeMediaPreProcessor` returning
  `Image`, `AnyFile`, `Audio`. It asserts the items are `[GalleryItemInfo.Image, MediaFile, Audio]` in order, the caption
  is `"Q3 docs"` exactly once, and `sendPreProcessedMedia` is never called.
- E2E J2 (encrypted) and J6 (plaintext): bot `gallery-last` projections. Every row of the J2 table below must match.

_Pass when_ the test passes and both projections match.

**AC3 — Every composer entry path and context.** A mixed gallery is sent as one event from:

| Cell | Context | Entry | Evidence |
| --- | --- | --- | --- |
| P1 | Encrypted room, main timeline | Attach → Files, 5 mixed files | J2 |
| P2 | Encrypted room, thread | Thread composer Attach → Files, 2 mixed files | J5 (`m.relates_to.rel_type = "m.thread"`, `event_id` = J2's event) |
| P3 | Plaintext room, main timeline | Attach → Files, 2 mixed files | J6 (`url` on every item, no `file`) |
| P4 | Plaintext room, main timeline | Attach → Gallery (photo picker), 2 images | J7 (image-only gallery with no Labs change; regression) |
| P5 | Share from another app (DocumentsUI) → encrypted room | 2 mixed files | J10 (AC6) |

Replying with attachments is unchanged: `inReplyToEventId` is still passed through the existing navigator parameters.
The existing test `present - Pick multiple media from gallery while editing keeps the edit pending` is updated only to
drop the flag, and keeps passing.
_Pass when_ every cell passes.

**AC4 — 60-item limit (D5).** Picking 61 files posts the snackbar `You can send up to 60 items in one message.` and does
not navigate. The composer mode, including Reply, and the text are unchanged, and nothing is sent. Picking exactly 60
opens the preview with `Item 1 of 60`.
_Evidence:_ `MessageComposerPresenterTest`:

- `present - Pick 61 files posts the gallery limit snackbar and keeps the reply mode`: the navigator is not called,
  `snackbarDispatcher` posts `screen_room_attachments_limit`, and the mode is still `Reply`;
- `present - Pick 60 files previews all of them`.

E2E J9: the 61-file selection shows the snackbar and `count-events --since mark --sender alice` = 0. Selecting 60 shows
`Item 1 of 60`, and Back afterwards sends nothing (count still 0).
_Pass when_ these hold.

**AC5 — Send failure, retry and cancel for mixed galleries.** When `sendGallery` fails, the preview shows the existing
failure dialog and no `Done`. Retry from `ReadyToUpload` sends exactly one more gallery and reaches `Done`, without
re-running pre-processing. Cancel before upload sends nothing and cleans up the pre-processed files.
_Evidence:_ `AttachmentsPreviewPresenterTest`, real `DefaultMediaSender` and `FakeTimeline`:

- `present - mixed gallery send failure then retry sends one gallery`: the first `sendGalleryLambda` returns a failure
  and the second a success. Expect the states `Failure` → `CancelAndClearSendState` → `ReadyToUpload` → `Sending` →
  `Done`, `sendGalleryLambda` called twice, and `preProcessMedia` called once per item.
- `present - mixed gallery cancel and dismiss sends nothing and cleans up`.

Network fault injection is not run on the device (see §5 limits).
_Pass when_ both tests pass.

**AC6 — Share (D4).**

- 2–60 shared files → per selected room, each file is pre-processed in order, then exactly one `sendGallery` is sent
  with `caption = shareIntentData.text`.
- 1 file → one `sendMedia` (unchanged).
- 61 files → 61 `sendMedia` calls (unchanged).
- A pre-processing failure in a room sends nothing in that room, cleans up, and yields the existing share error.
- Cancellation cleans up and rethrows (non-active rooms are destroyed as today).

_Evidence:_ `SharePresenterTest`:

- `present - share several files sends one gallery per room with the text caption once` (two rooms → two `sendGallery`
  calls, each with caption `A_MESSAGE` and 3 infos in order, no `sendMedia`);
- `present - send media ok` (existing, single file);
- `present - share more than 60 files keeps separate sends`;
- `present - share gallery preprocessing failure sends nothing and reports failure`;
- `present - share gallery cancellation cleans up and rethrows`.

E2E J10: DocumentsUI multi-select `report.pdf` + `red.png` → Share → Element X → the encrypted room. Exactly one Alice
event, `dm.filament.gallery`, with both names and SHA-256 matches. Shared text cannot be injected from DocumentsUI, so
the caption is covered by the unit test only.
_Pass when_ all pass.

**AC7 — Timeline rendering (D2).** A gallery `[red.png, report.pdf, tone.wav, notes.txt, noext]` renders one grid tile
`red.png`, then 4 rows in order. Each row shows the name and `<EXT> • <size>` (empty extension → size only, as in the
existing attachment row). A 7-image + 2-file gallery shows 5 tiles with `+2` and 2 rows. Galleries with only images keep
today's layouts and baselines. A gallery of only files/audio still renders `TimelineItemAttachmentsListView`.
Long-press on a row opens the message action list.
_Evidence:_

- `TimelineItemContentMessageFactoryTest`:
    - `test create GalleryMessageType with image, file and audio items returns TimelineItemGalleryContent with every item in order`
      (types, `formattedFileSize`, `fileExtension`, `visualItems` indices `[0]`, `fileItems` indices `[1, 2]`);
    - `test create GalleryMessageType mixed with visual items lacking thumbnails returns TimelineItemAttachmentsContent`;
    - `test create GalleryMessageType with only file and audio items returns TimelineItemAttachmentsContent with item types`;
    - all existing gallery tests (`:816-1081`) unchanged and passing.
- New Compose test `TimelineItemGalleryViewTest` (Robolectric, real `TimelineItemGalleryView`):
    - `mixed gallery renders visual tiles and file rows and reports item indices` (click tile `red.png` → 0, row
      `report.pdf` → 1, row `noext` → 4);
    - `overflow counts visual items only`;
    - `long click on a file row invokes onLongClick`.
- New previews in `TimelineItemGalleryContentPreviewParam`, **appended** after the existing six states: `mixed 1
  image + 4 files`, `mixed 2 images + 2 files`, `7 images + 2 files`.
- E2E J2 and J4 timeline observations.

_Pass when_ all assertions pass and the visual check in §4 holds.

**AC8 — Viewer opens each item with its real type (D3).** In a mixed gallery, tapping a row or tile opens the gallery
viewer at that item's index, with the items in event order and the real types: Image page for `red.png`, PDF for
`report.pdf`, audio player for `tone.wav`, text for `notes.txt`, generic file for `noext`. Attachments content
(files-only, or images without thumbnails) also opens with real types.
_Evidence:_ new `GalleryItemDataMapperTest`:

- `gallery content maps every item with its type and order`;
- `attachments content maps each item with its real type, not File`.

`MessagesFlowNode` calls these mappers, so this is the real call path. E2E J3: from J2's tile, open `report.pdf`, swipe
to `tone.wav`, and back out. Then open `red.png`.
_Pass when_ these hold.

**AC9 — Receiving a Web-shaped mixed gallery and persistence.** A mixed encrypted gallery sent by another client
(matrix-js-sdk, Element Web's SDK) through T1 `ctl send-gallery mixed` renders as AC7. The content is `[green.png,
red.png, report.pdf, tone.wav]` with caption `from bot`. Expect a 2-tile grid, 2 rows and the caption. After
`am force-stop` and relaunch, the J2 and J4 galleries still render the same way.
_Evidence:_ E2E J4 and J12. On the base APK, C0 records the expected baseline failure for J4: 4 flat rows and no grid.
_Pass when_ J4 and J12 pass on the feature APK.

**AC10 — Caption edit on a mixed gallery.** Edit caption on J2's event (`Q3 docs` → `Q3 docs v2`) sends one `m.replace`
whose `m.new_content.itemtypes` matches J2's items in itemtype, body, size and source. The tile shows `Q3 docs v2`,
`(edited)` and the same rows.
_Evidence:_ E2E J8, using `gallery-last --replace`. The existing caption-edit unit tests in `MessageComposerPresenterTest`
keep passing.
_Pass when_ J8 passes.

**AC11 — Rust SDK and cross-client contract.** Android-sent events satisfy the item schema that Element Web's validator
(`isGalleryItemContent`) and Hermes read: `itemtype` ∈ {`m.image`, `m.video`, `m.audio`, `m.file`}, `body` a non-empty
string, `url` with `mxc://` or `file.url`/`key`/`iv`/`hashes`/`v = "v2"`, and `info.size` an integer. matrix-js-sdk
receives and decrypts exactly one event per send, and Android renders a gallery sent from matrix-js-sdk (J4).
_Evidence:_

- J2, J5, J6, J10 projections;
- T1 node test `gallery content matches the MSC4274 item schema accepted by Element Web`.

_Pass when_ every projection satisfies the schema.

**AC12 — Existing behaviour and quality.**

- Single media sends, image/video-only galleries, voice messages, location, polls, camera capture, the image editor,
  `sendAsFile` compression rules, attachment-list rendering for file-only galleries, and text surfaces (D7) behave as
  before.
- Every gate in §4 passes. Its only allowed screenshot differences are the listed new snapshot names.
- No new `android.util.Log`. Timber logs contain no captions or file names (J13).

_Evidence:_ the §4 receipts in DOC2 against the final HEAD.

### Introduced state and transition inventory

| Transition | Covered by |
| --- | --- |
| Picker returns 0 / 1 / 2–60 / 61+ URIs (Gallery and Files) | Existing cancel test; existing single tests updated to the multi picker; AC1; AC4 |
| Over-limit while in Reply/Edit mode: mode and draft preserved, no navigation | AC4 `…keeps the reply mode`; the existing edit-pending test |
| Preview: pre-process mixed → `ReadyToUpload` → `Sending` → `Done` | AC2 |
| Preview: send failure → retry from `ReadyToUpload` (no re-pre-process) → `Done` | AC5 |
| Preview: cancel/dismiss before upload → cleanup, nothing sent | AC5, J9 |
| Share: per-room pre-process loop → one gallery; failure mid-loop → cleanup, no send in that room; cancellation → cleanup, destroy non-active room, rethrow | AC6 |
| Share: multiple rooms → independent galleries | AC6 two-room test |
| Timeline: local echo → remote; caption edit remap (`m.replace`) keeps rows | J2, AC10 |
| Timeline: per-item content validation states (loading/invalid/not found/valid) on file rows | `TimelineItemGalleryViewTest` uses the existing validation path; existing `TimelineItemAttachmentsListView` behaviour unchanged |
| Persisted `feature.send_gallery_messages` value from earlier versions | Ignored; AC1 (no code reads it) |
| App restart / resync | J12 |
| Concurrent independent sends | Not introduced. Each preview/share send runs in the session scope as today. |

### Decision and doc evidence map

| Item | Evidence |
| --- | --- |
| D1 | AC1 grep and tests; J1; `git diff origin/develop -- features/preferences/impl/src/main/res` is empty (no `localazy.xml` edit) |
| D2 | AC7, AC9, J2, J4 |
| D3 | AC8, J3 |
| D4 | AC6, J10 |
| D5 | AC4, J9; `rg -n "const val MAX_GALLERY_ITEMS = 60" libraries/mediaupload/api` matches |
| D6 | `git diff --stat origin/develop -- features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/attachments/preview` lists no production file |
| D7 | `git diff origin/develop -- libraries/eventformatter libraries/push libraries/matrixui` is empty; summary covered in AC7's factory test |
| D8 | `git diff origin/develop -- features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/protection` is empty |
| D9 | Existing `present - send media with pre-processing failure …` tests pass unchanged |
| D10 | `git diff origin/develop -- gradle/libs.versions.toml libraries/rustsdk libraries/matrix` is empty; `git diff --name-only origin/develop...HEAD` only lists paths in this repository |
| D11 | AC1 |
| DOC1 | Inspection: the README section lists `device-fixtures`, `setup-room --plain`, `send-gallery`, `gallery-last`, `ELEMENT_X_E2E_STATE_DIR` and the `build/e2e/mixed-file-gallery/` evidence root |
| DOC2 | Exists, cites the final HEAD and every §4 gate and §5 step |

## 2. Design

### Data flow

```
Attach → Gallery ─ ImageAndVideoMulti(60) ─┐
Attach → Files   ─ FileMulti(*/*) ─────────┼─ handlePickedMediaList(uris, sendAsFile)
                                           │    0 → nothing; 1 → handlePickedMedia (unchanged)
                                           │    >60 → snackbar(screen_room_attachments_limit), return
                                           │    2..60 → navigateToPreviewAttachments(attachments, inReplyTo)
                                           ▼
AttachmentsPreviewPresenter (unchanged) ─ preProcess each by MIME ─ sendGallery(infos, caption)
   └ DefaultMediaSender.sendGallery → MediaUploadInfo.toGalleryItemInfo → RustTimeline.sendGallery → FFI

Share SEND_MULTIPLE ─ SharePresenter: 2..60 → per room: preProcess each → sendGallery(infos, text) → cleanUp
                                      1 or >60 → existing per-file sendMedia

Receive: EventMessageMapper → GalleryMessageType → TimelineItemContentMessageFactory
   visual non-empty && any visual thumbnail → TimelineItemGalleryContent(all items)
       → TimelineItemGalleryView: grid(visualItems) + rows(fileItems)
   else → TimelineItemAttachmentsContent(items with type) → TimelineItemAttachmentsListView (unchanged UI)
   click(index) → MessagesFlowNode.processGalleryEventClick → toGalleryItemData() (real types) → GalleryViewer
```

### Module boundaries

| Module | Change |
| --- | --- |
| `libraries/featureflag/api` | Delete `SendGalleryMessages` |
| `features/preferences/impl` | Delete the three `LabsPresenter` branches |
| `libraries/mediaupload/api` | Add `MAX_GALLERY_ITEMS` |
| `libraries/mediaupload/test` | `FakeMediaSender`: argument-capturing lambdas with defaults (existing call sites unchanged) |
| `features/messages/impl` | Composer pickers and limit; model, factory, gallery view rows; viewer mapper; string |
| `features/share/impl` | Gallery share |
| `libraries/mediapickers/api` | Unchanged (`PickerType` keeps its private 60 for the visual picker) |
| `libraries/matrix/*`, Rust SDK, `libraries/eventformatter`, `libraries/push`, `libraries/matrixui`, `attachments/preview/*` | Unchanged |
| `tools/interactive-ui/fixture-bot` | E2E tooling only (T1) |

### Exact interfaces

`libraries/mediaupload/api/src/main/kotlin/io/element/android/libraries/mediaupload/api/MediaSender.kt` (top level):

```kotlin
/** MSC4274's recommended maximum number of items in one gallery message. */
const val MAX_GALLERY_ITEMS = 60
```

`libraries/mediaupload/test/.../FakeMediaSender.kt`: add trailing constructor parameters. The defaults delegate to the
existing zero-argument lambdas, so current tests compile unchanged.

```kotlin
private val preProcessMediaLambda: (uri: Uri, mimeType: String) -> Result<MediaUploadInfo> = { _, _ -> preProcessMediaResult() },
private val sendGalleryLambda: (infos: List<MediaUploadInfo>, caption: String?) -> Result<Unit> = { _, _ -> sendGalleryResult() },
```

`TimelineItemGalleryContent.kt`:

```kotlin
data class TimelineItemGalleryContent(/* unchanged constructor */) : TimelineItemEventContent, TimelineItemEventMutableContent {
    /** Images and videos with their index in [items], shown in the grid. */
    val visualItems: ImmutableList<IndexedValue<GalleryItem>> = items.withIndex().filter { it.value.type.isVisualMedia() }.toImmutableList()
    /** Audio and file items with their index in [items], shown as rows below the grid. */
    val fileItems: ImmutableList<IndexedValue<GalleryItem>> = items.withIndex().filterNot { it.value.type.isVisualMedia() }.toImmutableList()
}

data class GalleryItem(
    /* existing fields unchanged */
    val formattedFileSize: String = "",
    val fileExtension: String = "",
)
```

`TimelineItemAttachmentsContent.kt`: `AttachmentItem` gains `val type: GalleryItem.Type = GalleryItem.Type.File`.

`TimelineItemContentMessageFactory` (`GalleryMessageType` branch):

- Every mapped `GalleryItem` sets `formattedFileSize = fileSizeFormatter.format(info?.size ?: 0L)` and `fileExtension =
  fileExtensionExtractor.extractFromName(filename)`.
- The condition becomes `val visual = galleryItems.filter { it.type.isVisualMedia() }; if (visual.isNotEmpty() &&
  visual.any { it.thumbnailSource != null })`. This is identical to today when every item is visual.
- Each `AttachmentItem` sets `type` from its `GalleryItemType`.

`TimelineItemGalleryView.kt`:

- The signature is unchanged.
- The layout `when` switches on `content.visualItems.size`.
- The layouts take `ImmutableList<IndexedValue<GalleryItem>>` and call `onGalleryItemClick(indexed.index)`. Overflow is
  `visualItems.size - MAX_TILES`.
- When `fileItems` is non-empty, a `Column(Modifier.width(GALLERY_WIDTH))` follows the grid, spaced `GRID_SPACING`.
  Each item gets a `GalleryFileRow`:

```kotlin
@Composable
private fun GalleryFileRow(eventId: EventId?, item: GalleryItem, onClick: () -> Unit, onLongClick: (() -> Unit)?)
```

`GalleryFileRow` uses the same validation combination as `GalleryItemCell`. Invalid → `InvalidContentView`,
unrecoverable → `NotFoundContentView`, loading → `CircularProgressIndicator`, valid → `AttachmentRow`.

`TimelineItemAttachmentsListView.kt` extracts the private `AttachmentListItem` body into an internal composable shared
by both views. The rendered output for the existing list is identical, so its existing snapshots must not change.

```kotlin
@Composable
internal fun AttachmentRow(
    filename: String,
    mimeType: String,
    thumbnailSource: MediaSource?,
    subtitle: String,                 // "${fileExtension} • ${formattedFileSize}", as today
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,       // null → Modifier.clickable (existing list); non-null → combinedClickable (gallery rows)
    modifier: Modifier = Modifier,
)
```

New `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/model/event/GalleryItemDataMapper.kt`:

```kotlin
internal fun TimelineItemGalleryContent.toGalleryItemData(): List<GalleryItemData>
internal fun TimelineItemAttachmentsContent.toGalleryItemData(): List<GalleryItemData>
internal fun GalleryItem.Type.toMediaViewerType(): GalleryItemData.Type   // moved from MessagesFlowNode.kt:982
```

`MessagesFlowNode.processGalleryEventClick` replaces both inline `map { … }` blocks with these calls and keeps the
existing `.reversed()` at the call site.

`MessageComposerPresenter`:

- Delete `isSendGalleryMessagesEnabled`, `galleryMediaPicker` and `fileSinglePicker`.
- `FromGallery` → `galleryMultiMediaPicker.launch()`; `FromFiles` → `filesPicker.launch()`.
- `handlePickedMediaList` adds, after the empty check:

```kotlin
if (uris.size > MAX_GALLERY_ITEMS) {
    snackbarDispatcher.post(SnackbarMessage(R.string.screen_room_attachments_limit))
    return
}
```

`features/messages/impl/src/main/res/values/temporary.xml`:
`<string name="screen_room_attachments_limit">You can send up to 60 items in one message.</string>`

`SharePresenter`:

- `ShareIntentData.Uris` with `uris.size in 2..MAX_GALLERY_ITEMS` → for each room, `mediaSender.sendSharedGallery(…)`.
  All other sizes go to the existing branch.
- The existing room-destroy and cancellation handling stays around the new call.

```kotlin
/** Pre-process every file in order, then send them as one gallery; always cleans up. */
private suspend fun MediaSender.sendSharedGallery(files: List<UriToShare>, caption: String?): Result<Unit>
```

It calls `preProcessMedia(uri, mimeType, mediaOptimizationConfigProvider.get())` for each file. The first failure stops
the loop and is returned (a `CancellationException` is rethrown). On success it calls `sendGallery(infos, caption,
formattedCaption = null, inReplyToEventId = null)`. `finally` calls `cleanUp()`.

`FeatureFlags.kt`: delete the `SendGalleryMessages(...)` entry. `LabsPresenter.kt`: delete its three `when` branches.

### Errors

| Case | Behaviour |
| --- | --- |
| More than 60 picked in composer | Snackbar, no preview, no send (D5) |
| Pre-processing failure (any item) | Existing preview `Failure` dialog; share: room fails → existing share error dialog |
| Upload/send failure | Existing `SendActionState.Failure` + retry (preview); share error (share) |
| File larger than the server limit (non-visual) | Existing `displayFileTooLargeError` dialog |
| Item media invalid/not found (content scanner) | Per-row `InvalidContentView`/`NotFoundContentView`, as in attachment lists |
| Unknown item type (`GalleryItemType.Other`) | Dropped from both mappings (unchanged) |

### Conventions

- Kotlin/Compose/Molecule. Events go through the existing `eventSink`; there are no new states or events.
- Use `tryOrNull`, not `runCatching`. Keep the existing `runCatchingExceptions` call sites. Lines stay within 160
  characters, and new files carry the existing license header (`Copyright (c) 2026 Element Creations Ltd.`).
- Compound tokens/icons only. `@PreviewsDayNight` + `ElementPreview` + the existing `PreviewParameterProvider`. New
  states are appended so existing snapshot indices are preserved.
- Timber only. Never log captions, file names or URIs; use the existing `hash()` helper for paths.
- Strings go in `temporary.xml`; `localazy.xml` is never edited.
- PR: sentence-style title, one `pr-` label, and `Record-Screenshots` because previews change. Production additions
  must stay under 500 (estimate ≈300).

## 3. Tasks

### T1 — Mixed-gallery E2E fixture tooling

- **Split:** separate producer — capability probe C0 must run with this tooling against the **unmodified base app**
  before T2's substantial implementation. The independent verifier runs C0 between T1 and T2, so one worker cannot
  produce both without bypassing that gate.
- **Goal:** add commands to the fixture bot that observe and send mixed galleries, plus isolated state and device
  fixtures.
- **Owned files:**
    - `tools/interactive-ui/fixture-bot/bot.mjs`
    - `tools/interactive-ui/fixture-bot/lib/local.mjs`
    - `tools/interactive-ui/fixture-bot/lib/gallery-fixtures.mjs` (new)
    - `tools/interactive-ui/fixture-bot/lib/gallery-fixtures.test.mjs` (new)
    - `tools/interactive-ui/fixture-bot/README.md` (DOC1)
- **Produced interfaces:**
    - `local.mjs`: `localDir = process.env.ELEMENT_X_E2E_STATE_DIR ?? join(homedir(), '.local/share/element-x-e2e/interactive-ui')`.
      The default is unchanged.
    - The evidence allowlist adds `build/e2e/mixed-file-gallery/`. Prefixed siblings are still rejected.
    - `device-fixtures --out <dir>` runs locally (no server). It writes the AC fixture set and `manifest.json` `{files:[{name, mime, size, sha256}]}`.
    - `ctl setup-room --plain` creates a second, unencrypted room `Mixed gallery plain` and invites Alice. Unencrypted
      sends are allowed only to that room; the default room keeps its encrypted-only guard.
    - `ctl send-gallery mixed [--thread <event-id>]`: the bot uploads `green.png`, `red.png`, `report.pdf` and
      `tone.wav` (encrypted, `application/octet-stream`). It sends `{msgtype:"dm.filament.gallery", body:"from bot",
      itemtypes:[…]}` in the Web/SDK item shape and returns `{event_id, wire_type, encrypted}`.
    - `ctl gallery-last [--room plain] [--since mark] [--sender alice] [--replace] [--caption <text>]` returns a
      redacted projection: `{event_id, wire_type, encrypted, msgtype, caption_length, caption_equals,
      has_formatted_body, relation:{rel_type, event_id}, mentions, items:[{itemtype, body, mimetype, size, has_url,
      url_is_mxc, has_file, file_v, file_keys_complete, plaintext_sha256}]}`. For `--replace`, the projection is taken
      from `m.new_content`. The bot downloads every item through authenticated media and decrypts it with
      `decryptAttachment`. It never returns caption text.
- **Consumed:** the existing `attachment.mjs` (`encryptAttachment`, `decryptAttachment`, `solidPng`), `sendContent`,
  marks, `count-events`.
- **Tests** (`gallery-fixtures.test.mjs`):
    - `gallery fixtures are deterministic and match the manifest`
    - `wav fixture encodes 1 s 8 kHz 16-bit mono`
    - `pdf fixture is a single-page PDF`
    - `gallery projection reports caption equality without caption text`
    - `gallery content matches the MSC4274 item schema accepted by Element Web`
    - `evidence allowlist accepts mixed-file-gallery and rejects prefixed siblings`
    - `state directory honours ELEMENT_X_E2E_STATE_DIR`
- **Dependencies:** none.
- **Unit command:** `(cd tools/interactive-ui/fixture-bot && npm ci && node --test)`

### T2 — Mixed-file galleries in the Android app

- **Goal:** D1–D9 with all unit/Compose tests and previews (§1 evidence).
- **Owned files:**
    - `libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt`
    - `features/preferences/impl/src/main/kotlin/io/element/android/features/preferences/impl/labs/LabsPresenter.kt`
    - `libraries/mediaupload/api/src/main/kotlin/io/element/android/libraries/mediaupload/api/MediaSender.kt`
    - `libraries/mediaupload/test/src/main/kotlin/io/element/android/libraries/mediaupload/test/FakeMediaSender.kt`
    - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/`:
        - `messagecomposer/MessageComposerPresenter.kt`
        - `timeline/model/event/{TimelineItemGalleryContent.kt, TimelineItemAttachmentsContent.kt, GalleryItemDataMapper.kt (new)}`
        - `timeline/factories/event/TimelineItemContentMessageFactory.kt`
        - `timeline/components/event/{TimelineItemGalleryView.kt, TimelineItemAttachmentsListView.kt, TimelineItemGalleryContentPreviewParam.kt}`
        - `MessagesFlowNode.kt`
    - `features/messages/impl/src/main/res/values/temporary.xml`
    - `features/share/impl/src/main/kotlin/io/element/android/features/share/impl/SharePresenter.kt`
    - Tests under `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/`:
        - `messagecomposer/MessageComposerPresenterTest.kt`
        - `attachments/AttachmentsPreviewPresenterTest.kt`
        - `timeline/factories/event/TimelineItemContentMessageFactoryTest.kt`
        - `timeline/components/event/TimelineItemGalleryViewTest.kt` (new)
        - `timeline/model/event/GalleryItemDataMapperTest.kt` (new)
    - `features/share/impl/src/test/kotlin/io/element/android/features/share/impl/SharePresenterTest.kt`
- **Consumed:** existing `MediaSender`, `Timeline.sendGallery`, `FakeTimeline.sendGalleryLambda`,
  `FakeMediaPreProcessor`, `FakePickerProvider.givenMultipleResults`, `SnackbarDispatcher`.
- **Produced:** the §2 interfaces.
- **Dependencies:** T1, plus the C0 record in `$RUN/verification.md`. C0 must show a real observation: either a pass or
  an expected baseline feature failure, not an inability to observe.
- **Unit command:**
  `nix develop --command ./tools/gradle-local.sh :features:messages:impl:testDebugUnitTest :features:share:impl:testDebugUnitTest :features:preferences:impl:testDebugUnitTest :libraries:mediaupload:impl:testDebugUnitTest :libraries:featureflag:impl:testDebugUnitTest`

**Waves:** T1 → (verifier: C0) → T2 → consolidated review of T1+T2 → verifier gates (§4) and E2E (§5).

- There is no parallel work: T2 depends on the C0 gate, and all builds share the `gradle-local.sh` lock.
- External dependencies: none in code. The E2E environment needs downloads (see §4 prerequisites).
- Independent work: T1 does not touch Gradle.

## 4. Test and verification

Every Gradle command is run by the **independent verifier** from the worktree root. Each runs as
`nix develop --command ./tools/gradle-local.sh <tasks>`, through the validated `qex-e2e android-sandbox` claim (8 CPU,
24 GiB, 2 h). The commands serialize on `~/.cache/element-x-android/gradle.lock`. No Gradle job overlaps another, and
none overlaps the emulator. Evidence goes in `build/e2e/mixed-file-gallery/<UTC>/` (ignored), with a summary copied to
`$RUN/verification.md` and DOC2.

### Prerequisites and generated inputs

- `nix develop` provides JDK 21, build tools 37 and the compile SDK.
- Screenshot goldens must be real LFS files, not pointer files: run
  `git lfs pull --include "tests/uitests/src/test/snapshots/images/**"`. This mutates only LFS objects in this worktree.
  Then check that `file tests/uitests/src/test/snapshots/images/features.messages.impl.timeline.components.event_TimelineItemGalleryView_Day_0_en.png`
  reports PNG.
- Run `(cd tools/interactive-ui/fixture-bot && npm ci)`. This mutates `node_modules` in the worktree, which is ignored.
- E2E environment. None of these exist on kazuki today; verified read-only on 2026-11-16:
    - `~/.local/share/element-x-e2e/android-sdk` is absent: no emulator binary, no API 33 `google_apis` x86_64 image,
      no AVD;
    - `matrixdotorg/synapse` is not pulled;
    - `orca`, `orca-ide` and `maestro` are not on PATH.

  The verifier installs the SDK emulator and image into that dedicated path and pulls the Synapse image (network
  downloads). Use `steam-run` for the emulator on NixOS, per the fixture README. Without Orca, use `adb -s emulator-5584`
  plus `uiautomator dump` for observation (skill: "outside Orca"). A missing dependency is reported as a blocker.
- Isolated accounts: `~/.local/share/element-x-e2e/interactive-ui/credentials.json` exists from an earlier sandbox whose
  Synapse data is not in this worktree. **Do not read, reuse or delete it.** Export
  `ELEMENT_X_E2E_STATE_DIR=$HOME/.local/share/element-x-e2e/mixed-file-gallery` (owner-only, 0700) for `provision`,
  `serve` and `ctl`.

### Gates (final HEAD)

| # | Check | Command | Pass |
| --- | --- | --- | --- |
| G1 | Tooling tests | `(cd tools/interactive-ui/fixture-bot && npm ci && node --test)` | All pass, including the 7 new tests |
| G2 | Feature unit/Compose tests | T2 unit command | All pass; every test named in §1 present and passing |
| G3 | Full unit regression (all modules consuming the changed `FeatureFlags`, `FakeMediaSender` and `MediaSender`) | `:app:testGplayDebugUnitTest testDebugUnitTest -x :tests:konsist:testDebugUnitTest` | All pass |
| G4 | Consumer compile, both flavours (DI graph through Metro) | `:app:compileGplayDebugKotlin :app:compileFdroidDebugKotlin` | Success |
| G5 | Konsist | `:tests:konsist:testDebugUnitTest` | Pass |
| G6 | Static analysis/format | `detekt ktlintCheck` | Pass (`ktlintFormat` is the only allowed repair, by the T2 owner) |
| G7 | Android lint | `:app:lintGplayDebug :app:lintFdroidDebug lintDebug --continue` | No new issues (`UnusedResources` ignores `localazy.xml`) |
| G8 | Code quality script | `./tools/check/check_code_quality.sh` | Pass (it may download its helper into ignored `tmp/`) |
| G9 | Screenshot verify (**no recording**) | `:tests:uitests:verifyPaparazziDebug` | Pass except missing goldens for the appended `TimelineItemGalleryView_{Day,Night}_{6,7,8}_en` states. Every other existing image, including `TimelineItemAttachmentsListView_*` and `TimelineItemGalleryView_*_{0..5}`, is unchanged. Any other diff fails. CI records the new goldens via `Record-Screenshots`. |
| G10 | Coverage | `:koverXmlReportMerged :koverHtmlReportMerged :koverVerifyAll -x testDebugUnitTest` (after G3) | `koverVerifyAll` passes |
| G11 | Debug APK | `:app:assembleGplayDebug` | `app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk` exists |
| G12 | Scope greps | `git grep -n -e SendGalleryMessages -e send_gallery_messages -- '*.kt'`; the D-map `git diff` commands | As in §1 |

Repair ownership: T2's owner fixes failures in T2 files, and T1's owner in tooling. The verifier does not edit
production code. Protected boundaries: no `localazy.xml` edits, no baseline recording, no SDK/AAR changes, no
edits outside this worktree.

### Capability probe C0 (after T1, before T2; base app at `0b30b979f4`)

The capability is Android DocumentsUI multi-select into the real preview and send path, observed by a second real Matrix
client.

1. Build the base APK with G11 on the base checkout of this branch (T1 commit only). Start Synapse, provision
   (isolated state), boot the AVD, install with `adb -s emulator-5584 install -r --no-incremental`, and confirm
   `firstInstallTime`/`lastUpdateTime`. Then `adb reverse tcp:18028 tcp:18028`, log in as Alice and run
   `setup-room` + `setup-room --plain`. Push the device fixtures to `/sdcard/Download/mfg/` and trigger a media scan.
   - **Readiness:** `getprop sys.boot_completed` = 1, the room is open, and the `uiautomator` dump shows the composer.
2. Labs → enable **Gallery messages**. This exists on base and is restored after C0.
3. In the encrypted room: Attach → Files. DocumentsUI → Downloads/mfg. Long-press `red.png` (adb `input swipe x y x y
   800` if Orca is unavailable), tap `report.pdf`, then Select. The preview shows `Item 1 of 2`. Caption `c0`, Send.
4. Observation: `ctl count-events --since mark --sender alice` = 1. `ctl gallery-last --sender alice --caption c0` shows
   `dm.filament.gallery`, `[m.image red.png, m.file report.pdf]`, `caption_equals`, `file_keys_complete`, and
   `plaintext_sha256` matching the manifest.
5. `ctl send-gallery mixed`. Within 30 s, the dump shows 4 rows (`green.png`, `red.png`, `report.pdf`, `tone.wav`) and
   no grid tiles.

C0 passes when steps 3–4 pass. Step 5 is the **expected baseline feature failure** (flat list), recorded as observed.
C0 fails as **unable to observe** when any of these happens: DocumentsUI multi-select cannot be driven, the dump has no
file names, or the bot cannot decrypt. Record the outcome in `$RUN/verification.md`, then reset Labs to off.

## 5. E2E journey (first addition only)

Runs once, on the final feature APK, by the independent verifier, following `.agents/skills/element-x-android-e2e/SKILL.md`.
Future pushes and deployments do not rerun it; existing unit tests (G2/G3) still run.

- **Setup S0:** same as C0 step 1, but with the feature APK. Reuse the C0 sandbox, Alice's session and the dedicated AVD.
  Never clear app data; install the update in place. Start the bot with `serve --evidence
  build/e2e/mixed-file-gallery/<UTC>`. Mark before each step.
- **Readiness for each step:** a bounded wait (30 s) on a concrete dump condition.
- **Evidence for each step:** screenshot and `uiautomator`/Orca dump, plus the bot JSON, saved under the evidence dir.
- **Rules:** never put secrets on the command line or in artifacts.

| Step | Path | Actions | Expected |
| --- | --- | --- | --- |
| J1 | Labs | Settings → Labs | No `Gallery messages` entry (AC1). |
| J2 | Encrypted room, Files picker, 5 mixed (P1) | Attach → Files → select `red.png`, `report.pdf`, `tone.wav`, `notes.txt`, `noext` in that order → swipe through the preview → caption `Q3 docs` → Send | Preview pages: image, PDF, audio player, text, file; pill `Item n of 5`. Exactly 1 Alice event. `gallery-last --caption "Q3 docs"`: wire `m.room.encrypted`, `dm.filament.gallery`, `caption_equals`, items `[m.image red.png image/png, m.file report.pdf application/pdf, m.audio tone.wav audio/*, m.file notes.txt text/plain, m.file noext]`, each with size and `plaintext_sha256` = manifest, `has_file`, `!has_url`, `file_v "v2"`, `file_keys_complete`. Timeline: 1 tile `red.png`, then 4 rows in order with `<EXT> • <size>` (AC2, AC3, AC7, AC11). |
| J3 | Viewer | Tap row `report.pdf` → swipe to next → Back → tap tile `red.png` | PDF page titled `report.pdf`; next page `tone.wav` with an audio play control; image page for `red.png` (AC8). |
| J4 | Receive | `ctl send-gallery mixed` | 2 tiles (`green.png`, `red.png`), 2 rows (`report.pdf`, `tone.wav`), caption `from bot` (AC9). |
| J5 | Thread (P2) | On J2 → Reply in thread → Attach → Files → `notes.txt`, `green.png` → caption `in thread` → Send | 1 event; `gallery-last` relation `m.thread` → J2 id; items `[m.file notes.txt, m.image green.png]`; the thread view shows 1 tile + 1 row (AC3). |
| J6 | Plaintext room (P3) | Open `Mixed gallery plain` → Attach → Files → `report.pdf`, `red.png` → Send (no caption) | `gallery-last --room plain`: wire `m.room.message`, `caption_length 0`, items `[m.file report.pdf, m.image red.png]`, `has_url`, `url_is_mxc`, `!has_file`, sha matches (AC2, AC3). |
| J7 | Plaintext room, photo picker (P4) | Attach → Gallery → select `red.png`, `green.png` → Send | 1 image-only gallery; timeline grid unchanged (AC3, AC12). |
| J8 | Caption edit | Long-press J2 → Edit caption → `Q3 docs v2` → Send | `gallery-last --replace --caption "Q3 docs v2"`: `caption_equals`, items equal to J2's projection; the tile shows `(edited)` and the same rows (AC10). |
| J9 | Limit | Attach → Files → `mfg/bulk` → Select all (61) → Select. Then Attach → Files → Select all → deselect `bulk-61.txt` → Select → Back | Snackbar `You can send up to 60 items in one message.`; no preview; count 0. Second attempt: `Item 1 of 60`; after Back, count still 0 (AC4). |
| J10 | Share (P5) | Launch DocumentsUI → Downloads/mfg → select `report.pdf`, `red.png` → Share → Element X → encrypted room → Send | Exactly 1 Alice event, `dm.filament.gallery`, both names, sha matches (AC6). |
| J11 | Regression | Send a single `notes.txt` via Attach → Files (one item) | One `m.file` event (not a gallery); `TimelineItemFileView` tile (AC12). |
| J12 | Persistence | `am force-stop io.element.android.x.debug`, relaunch, open the encrypted room | J2/J4/J8 galleries render as before (AC9). |
| J13 | Privacy | `logcat --pid <app pid> -t 2000` (local only) | 0 matches for `Q3 docs`, `report.pdf`, `notes.txt` (AC12). |

**Limits (recorded, not waived):**

- Network fault injection for send failure/retry is not run on the device. The Rust send queue retries internally,
  which makes the outcome nondeterministic. Failure and retry are covered by AC5's real-sender tests.
- Shared text cannot be injected from DocumentsUI, so the caption-once rule is covered by AC6's unit test.
- Element Web rendering of Android-sent events is not executed. It would need a separate Web environment, so the
  contract is checked at the field level (AC11) against matrix-js-sdk, the SDK Element Web uses.
- Video items are not in the device fixtures. Video grid behaviour is unchanged and covered by the existing tests.

**Cleanup:**

1. `ctl shutdown`.
2. `adb -s emulator-5584 reverse --remove tcp:18028`.
3. Delete `/sdcard/Download/mfg` on the dedicated AVD only.
4. Restore any device settings that were changed.
5. Run `synapse-down.sh`.
6. Shut down the AVD only if this run started it.
7. Keep the Synapse data, the isolated state dir and the AVD for reruns.
8. Copy the evidence summary to `$RUN`.

Never touch the older `interactive-ui` credentials or other worktrees' sandboxes.

## 6. Approval

- Reviewed revision: this file as presented, uncommitted on branch `curly-seal` at HEAD `0b30b979f4`, sha256
  `9f95b8d7c8cb770db3fdb9f64f1b21bc4b3725f5a4cac668370fb4bcbfea8255`. The only later edits are this §6 record and the
  Status line (editorial; no scope, design or AC change).
- User's explicit approving response: "Approve" (ask_user final-approval prompt, 2026-11-16, reconfirmed in a direct
  follow-up prompt naming the sha256 above).
