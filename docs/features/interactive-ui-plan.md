# Interactive UI in Element X Android (A1) — design plan

> Status: **APPROVED by the user (2026-11-03)**, including every recommended ruling (R1–R23; R15 and R17 confirmed). Nothing here is implemented yet.
>
> Workspace: `/home/sinkerine/.paseo/worktrees/3b36k2jo/interactive-ui-android`, branch `interactive-ui-android`, base `develop`
> (merge-base `816048e6c156099006a5c904d6879cab42b3f153`).
>
> Canonical contract: Element Web, local `develop` at `bcb080171cb312feef37e63007c7ca8cc0a8b77f`
> (`/home/sinkerine/dev/element-web`, read-only): `docs/features/interactive-ui-plan.md` §2.3/§2.4/§2.7/§3.5/§5C,
> `docs/features/interactive-ui-verification.md`, `apps/web/src/interactive-ui/{protocol,state,action}.ts`, the v1 schema
> and the `fixtures/{valid,invalid}` conformance set.

**Scope honesty.** The intended pipeline is GPT → Hermes → Matrix → Element Web / Element X Android. Only the Element Web
consumer exists and is verified. This plan delivers **A1, the Element X Android consumer**. Hermes (**H1**, the producer
and action handler) is a separate, unimplemented and unverified workspace. The Android e2e journey uses a scripted,
encrypted **fixture bot** as a stand-in sender. It does not prove that GPT or Hermes produce or handle these events, and
this plan never claims the full pipeline works.

## Verified facts that shape the design

| Fact                                                                                                                                                                                                                                                                                     | How it was verified                                                                                                     |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| The app builds against a **local Rust SDK AAR** at `libraries/rustsdk/matrix-rust-sdk.aar`; `libraries/matrix/impl/build.gradle.kts` refuses to build without it. The file is gitignored and **missing in this worktree**. The main checkout has one (sha256 prefix `1ac85d968e24cc7a`). | `libraries/matrix/impl/build.gradle.kts:27`, `libraries/rustsdk/.gitignore`, `sha256sum`                                |
| That AAR exposes `RoomInterface.sendRaw(eventType: String, content: String, requestConfigOverride: RequestConfigOverride?): String` (suspend), and `LazyTimelineItemProvider.debugInfo()` with `originalJson`/`latestEditJson`.                                                          | Class-file inspection of the AAR's `classes.jar`                                                                        |
| `send_raw` encrypts in encrypted rooms but bypasses the send queue (no local echo). `debug_info().original_json` is the decrypted event; `latest_edit_json` is the latest *valid* (same-sender) edit. `debug_info().model` is a `{:#?}` dump of the item, so the call is not free.          | `~/dev/matrix-rust-sdk` `bindings/matrix-sdk-ffi/src/{room/mod.rs:471,timeline/mod.rs:1616}`, `matrix-sdk-ui` `event_item/mod.rs:504` |
| Android already maps debug info to `EventTimelineItem.timelineItemDebugInfoProvider` (lazy).                                                                                                                                                                                              | `EventTimelineItemMapper.kt:61`                                                                                         |
| Timeline items are cached in `TimelineItemsFactory` and rebuilt only when the SDK sends a diff for them. An edit (revision) is such a diff.                                                                                                                                              | `TimelineItemsFactory.kt`, `TimelineItemsCacheInvalidator.kt`                                                           |
| Per-item presenters already exist: `TimelineItemPresenterFactories` (room scope) and a `@TimelineItemEventContentKey` map multibinding, used by voice messages. The cache key is the content instance.                                                                                    | `timeline/di/*`, `VoiceMessagePresenterBindingContainer.kt`                                                             |
| Message bubbles are capped at 78 % of the row (`BUBBLE_WIDTH_RATIO`), about 213 dp of content on a 360 dp phone. Swipe-to-reply wraps the row in `SwipeSensitivity(3f)`, which also applies to child scrollables.                                                                       | `MessageEventBubble.kt:141,190`, `TimelineItemEventRow.kt:241`                                                          |
| `ConfirmationDialog`, `LinkChecker` (treats `text == url` as safe), `HtmlMessageParser` + `HtmlMessageContent` (native Compose HTML), `FormattedBody.toHtmlDocument` (Jsoup safelist), `MediaRequestData` + Coil, `PreferenceDataStoreFactory` and `SystemClock` all exist.               | Source reads                                                                                                            |
| Konsist requires every Kotlin file to carry the `Copyright (c) 20xx Element Creations Ltd.` header. Earlier fork features follow it.                                                                                                                                                      | `KonsistLicenseTest.kt`, `git log` of fork commits                                                                      |
| `MainActivity` handles `orientation|screenSize|screenLayout|uiMode` itself, so rotation and dark mode do not recreate the activity or the room scope. `allowBackup="false"`.                                                                                                             | `app/src/main/AndroidManifest.xml`                                                                                      |
| Sandbox tools on this host: Docker 29.8.1 with a local `matrixdotorg/synapse:latest` image; an Android SDK, the `system-images;android-33;google_apis;x86_64` image and Maestro under `~/.local/share/element-x-e2e/`; Node 24.21.0. Another worktree's Synapse (`element-x-thread-drawer-synapse`, port 18008) and AVD (`thread_my_messages_9282a45`) exist. **They are not reused.** | `docker`, `ls`, `node --version`                                                                                       |

## Rulings

Routine decisions taken while designing. Each is `decision — why — cost if wrong`. The user may overturn any of them;
R15 and R17 were confirmed explicitly by the user.

- **R1.** Ruling: the Android parser is a Kotlin port of Element Web's **schema interpreter**. It loads the vendored
  `interactive-ui-v1.schema.json` at runtime and then runs a port of the relational checks — Web's executable semantics
  are schema-driven, and porting the interpreter is the only way to reproduce the same first error, path and code for
  every fixture — cost if wrong: one more careful port (~300 lines) instead of a hand-written typed decoder.
- **R2.** Ruling: JavaScript semantics are reproduced explicitly. ECMAScript `trim()` whitespace; lengths in Unicode code
  points; schema `\s` mapped to the ECMAScript whitespace class; numbers as `Double` with `-0 == 0`; integers are safe
  integers (`1.0` is an integer); object keys iterated in JS order (canonical array-index keys ascending first, then
  insertion order); duplicate JSON keys keep the first position and the last value; JS `Number#toString` for action
  bodies — Kotlin's defaults differ in each case, and those differences change error precedence or wire text — cost if
  wrong: subtle fixture mismatches that the conformance tests catch.
- **R3.** Ruling: the WHATWG `new URL()` check (https, non-empty host, no credentials) uses OkHttp's `HttpUrl`, backed by
  an Android-only edge-case table generated once with Node's `new URL()` — `java.net.URI` rejects IDN hosts that Web
  accepts, and OkHttp follows the URL standard closely — cost if wrong: a rare URL is accepted or rejected differently
  from Web. The table makes any divergence visible.
- **R4.** Ruling: the protocol core is an `io.element.android-library` (no Compose), not `io.element.jvm-library` — CI
  runs `testDebugUnitTest`, and JVM modules only have `test`, so their conformance tests would never run in CI — cost
  if wrong: slightly slower unit tests.
- **R5.** Ruling: the new content type `TimelineItemInteractiveUiContent` implements `TimelineItemTextBasedContent` and
  wraps the normal `TimelineItemTextContent` fallback — copy, forward, reactions, reply previews, message summaries and
  pinned rendering keep working on the fallback text with almost no changes — cost if wrong: a few extra `when`
  branches if some text-only behaviour must be suppressed.
- **R6.** Ruling: raw JSON is read through the existing `timelineItemDebugInfoProvider()` only for remote `m.text`
  items while the flag is on. No Rust SDK change (R1-SDK in the Web plan stays optional) — the APIs exist, and
  `TimelineItemsFactory` caches items, so the call runs once per new or changed item — cost if wrong: if profiling on
  the emulator shows jank in text-heavy rooms, follow-up R1-SDK adds a cheap `latest_json()` accessor.
- **R7.** Ruling: actions are sent with `JoinedRoom.sendRaw("m.room.message", json)` and have no local echo. The card's
  "Sending…" state lasts until `sendRaw` returns the event id (homeserver acceptance) — this is the only API that keeps
  the custom key, and it matches D6 exactly — cost if wrong: the user's reply bubble appears only after the remote echo
  (normally well under a second).
- **R8.** Ruling: one `InteractiveUiSession` per event id in the **room scope**. Sends and timers run on
  `room.roomCoroutineScope`, not in the item's composition — timeline items leave composition when scrolled away, and a
  send or the 8-second timer must survive that. Revisions update the same session — cost if wrong: none expected; the
  room scope already outlives rotation.
- **R9.** Ruling: local state persists in a per-session Preferences DataStore
  (`elementx_interactive_ui_<sessionHash>`), one entry per `roomId|eventId`. Each entry is the Web envelope
  `{ui_id, revision, state}` with a timestamp, at most 64 KB. The store keeps at most 200 entries (oldest evicted) and is
  cleared when the session is deleted. Backups are already disabled — this is device-only, bounded and mirrors Web's
  localStorage — cost if wrong: drafts survive a little longer or shorter than expected.
- **R10.** Ruling: the IME action on single-line fields moves focus (`Next`) or closes the keyboard (`Done`); it **never
  submits** a form. Enter during composition is passed to the IME — on Android "Done" means "finished typing", and
  submitting would risk sending a single-use form by accident. Web submits on Enter on desktop keyboards — cost if
  wrong: one extra tap on the submit button.
- **R11.** Ruling: interactive messages use a full-width bubble (the whole row after the avatar column, capped at
  680 dp) with the timestamp below, like polls — forms, tables and charts are unreadable at 213 dp, and Web made the same
  call ("cards use full tile width") — cost if wrong: interactive bubbles look different from text bubbles.
- **R12.** Ruling: tables render the first 50 rows, then a "Show all N rows" button (view-local state) — a 200×12 table
  is 2,400 text nodes inside one lazy item, which would stall scrolling — cost if wrong: one tap to see long tables.
  Sorting always applies to all rows.
- **R13.** Ruling: images load full content (`MediaRequestData.Kind.Content`) through the Rust media loader, which
  decrypts `file` sources, and they respect the room's timeline media protection — this is the only path that decrypts,
  and protection parity avoids a privacy regression — cost if wrong: more bandwidth than server thumbnails for plain
  `mxc` images (at most 24 images).
- **R14.** Ruling: `open_url` and `link` always show our own confirmation dialog with the full https URL. Confirming
  calls the timeline's existing `onLinkClick(Link(url = url, text = url))`, which `LinkChecker` treats as safe, so there
  is no second dialog — this reuses the app's link opening (Custom Tabs, permalinks) without bypassing it — cost if
  wrong: an `https://matrix.to` link navigates inside the app instead of opening a browser, which is arguably better.
- **R15 (confirmed).** Ruling: every new Kotlin file carries the Konsist-required
  `Copyright (c) 2026 Element Creations Ltd.` header, as in earlier fork features. Each ported file has a KDoc line
  `Ported from element-web <path> at bcb08017 (Copyright 2026 sinkerine, AGPL-3.0-only OR LicenseRef-Element-Commercial).`
  Vendored JSON keeps its original notices, and `VENDORED.md` records the source, commit, licence and contributor —
  Konsist fails the build otherwise, and the Web plan says the holder should be the contributor for non-Element work.
  These conflict; the user confirmed this resolution — cost if wrong: header churn across about 40 files.
- **R16.** Ruling: the Labs flag `FeatureFlags.InteractiveUi` (`feature.interactive_ui`) defaults to off. Toggling it
  applies to timeline items built afterwards (reopen the room) — this matches Web's labs flag, and live re-rendering
  would mean invalidating the item cache — cost if wrong: the user must reopen a room after toggling.
- **R17 (confirmed).** Ruling: the e2e fixture bot lives in the repo at `tools/interactive-ui/fixture-bot/` (Node ESM,
  `matrix-js-sdk` with Rust crypto, an exact version pinned by `package-lock.json`, its own `.gitignore` for
  `node_modules`). It is test tooling, not production code — it gives repeatable encrypted journeys without GPT
  credentials. Node with WASM crypto is the most portable choice on NixOS, where libolm-based Python bots are fragile —
  cost if wrong: an npm toolchain inside an Android repo. The alternative is to keep it under ignored `build/e2e/`.
- **R18.** Ruling: before building, copy the main checkout's AAR (read-only source, sha256 prefix `1ac85d968e24cc7a`,
  the build that `develop` was verified with) to this worktree's ignored `libraries/rustsdk/matrix-rust-sdk.aar`, and
  record its hash in the evidence. If compilation shows an API mismatch, rebuild with `tools/sdk/build-threads-inbox-sdk`
  — the build cannot start without it, and copying an ignored artifact does not modify the main checkout — cost if
  wrong: a 96 MB copy, or a long Rust build.
- **R19.** Ruling: the sandbox is dedicated to this worktree: Synapse container `element-x-interactive-ui-synapse` on
  `127.0.0.1:18028`, data under this worktree's `build/e2e/interactive-ui/synapse`, server name `interactive-ui.test`, a
  new AVD `interactive_ui_a1`, and bot control on `127.0.0.1:18029` — other workspaces' containers, accounts and
  emulators hold their own state — cost if wrong: a few hundred MB of disk.
- **R20.** Ruling: number fields accept `.` or `,` as the decimal separator and keep the typed text while it is a
  partial number (`-`, `1.`, `,5`) — locale keyboards send `,`, and Web requires finite draft input to work — cost if
  wrong: none; values on the wire are always numbers.
- **R21.** Ruling: `rich_text.html` goes through `FormattedBody.toHtmlDocument` (the existing Jsoup safelist). Then
  `<a>`, `<details>` and `<summary>` are unwrapped and the result is drawn with `HtmlMessageParser` + `HtmlMessageContent`,
  whatever the state of `NewTimelineEventRenderer` — this is the same sanitiser as message bodies, and links and
  disclosures stay static as Web ruled — cost if wrong: minor visual differences from the legacy span renderer.
- **R22.** Ruling: a revision with a lower or equal `revision` is ignored. A latest edit without a valid payload shows the
  edited text as a normal message, and a redaction shows the redacted placeholder — this matches Web's `onReplaced`,
  with the timeline's own redaction handling — cost if wrong: none.
- **R23.** Ruling: the "Edit" and "Forward" actions are unchanged. Editing your own interactive event drops the payload,
  because SDK edits carry no custom keys, and it falls back to text — this keeps native behaviour, matching Web's
  "non-editing events only" ruling — cost if wrong: a user can deliberately turn their own card into text.

---

## 1. Goal and acceptance criteria

### Goal

With the Labs flag on, Element X Android renders any `m.room.message`/`m.text` event that carries a valid
`net.15cm.matrix.interactive_ui` v1 payload as a first-class, native Compose card. The card reaches parity with the
Element Web catalog and behaviour, is responsive from 360 dp phones to large screens, and is accessible with TalkBack.
Local interactions send nothing. Submit actions send a visible, encrypted reply with the
`net.15cm.matrix.interactive_ui.action` content defined by Web. Every other case falls back to the event's normal text.

### Acceptance criteria (all measurable; "fixture" means the vendored Web fixture set)

1. **Conformance.** All 22 valid fixtures parse to `Valid`. All 51 invalid fixtures return exactly the `{path, code}` in
   their `*.expected.json`. A generated 60,000-byte / 300-node payload parses in under 50 ms on the JVM unit-test host,
   and 60,001 bytes returns `too_large`. A seeded mutation run of at least 2,000 cases never throws. The vendored schema
   and fixtures match a recorded sha256 manifest from Web commit `bcb08017`.
2. **Wire parity.** For the same inputs, `InteractiveUiActionContentBuilder` output equals Web `buildActionContent`
   output. Golden JSON cases are generated once from Web's semantics for plain-room, thread, edited-UI (original
   `ui_event_id`), label/value body formatting, 1,000-code-point truncation, and number formatting.
3. **Rendering.** With the flag on, every valid fixture renders through the real timeline item path
   (`TimelineItemEventContentView`) without falling back. Each node kind has a Compose test. Previews cover every
   principal state in day and night.
4. **Fallback.** With the flag off, in reply previews, for local echoes, for `version != 1`, for every invalid fixture,
   and for edits whose latest content has no payload, the event renders as the normal text message. Only invalid
   payloads (not `bad_version`) add the caption "Couldn't show interactive content." No exception escapes the factory
   or composable.
5. **Local interactions.** `select`, `choice`, `toggle`, text and number inputs, tabs, collapsibles, graph selection,
   list mode, chart data mode, table sort and carousel moves change `visible_when`-gated content immediately and send
   **zero** Matrix events (the bot observes none). State survives scrolling away, rotation, dark-mode switch and an app
   restart on the same device. It survives a revision when the key, type and option still match.
6. **Actions.** Pressing a submit button, an item action or a form submit sends exactly one `m.room.message`. It
   carries a readable `body`, `m.mentions.user_ids == [UI sender]`, `m.relates_to.m.in_reply_to.event_id == UI event`
   and, in a thread, `rel_type: m.thread` with `is_falling_back: false`. The action object holds `version 1`, the
   original `ui_event_id`, `ui_id`, `revision`, `action_id`, only the declared values (option values, not labels) and a
   32-character base64url nonce from `SecureRandom`. Forms default to `single_use: true` and buttons to `false`.
7. **Lifecycle (D6).** "Sending…" lasts from the press until `sendRaw` returns. Repeatable actions then re-enable.
   Single-use actions show "Waiting for confirmation…" with controls disabled until a newer revision arrives. After
   8 s (virtual-time test, ±0 ms) the card shows "No confirmation from <sender> yet" and "Try again". "Try again"
   re-enables controls and sends nothing. A send failure re-enables controls with "Could not send your response".
   `submitted`/`expired`/`superseded` disable every control and show `status_text`.
8. **Validation UX.** A required empty field, an out-of-range number or a partial number blocks submission. It shows an
   inline error and sends nothing. Finite draft numbers (`-`, `1.`, `12,5`) can be typed without the field resetting.
   IME composition and "Done" never submit.
9. **Security.** No payload string becomes executable or clickable markup. `rich_text` links and disclosures are inert.
   Images load only from `mxc://` or encrypted `file` and decrypt through the Rust media loader. `open_url` and `link`
   require a dialog that shows the full https URL; Cancel opens nothing. Logs (Timber) contain only error codes and
   paths, never payload or user content.
10. **Responsive.** At 360 dp width and at 200 % font scale, the timeline has no horizontal page overflow. Card text
    wraps, controls keep at least 48 dp touch targets, and only graph, table, chart and carousel regions scroll
    horizontally. A horizontal drag inside them never starts swipe-to-reply or the threads drawer. On widths of 600 dp
    or more, the card is capped at 680 dp and horizontal stacks lay out side by side.
11. **Carousel.** "Item n of m" is visible and announced. Previous/Next work. A settled swipe moves exactly one item.
    The selected item stays the same across rotation, a width change (`wm size`/`density`) and dark mode.
12. **Accessibility.** Every control exposes its label, role and state to TalkBack (checked in the accessibility tree
    and with Compose semantics tests). Graph nodes are buttons in reading order, with a "Relationships as list"
    alternative. Charts expose a summary and a "Data table" alternative. Table headers expose sort state. Headings use
    `heading()`.
13. **Encrypted e2e.** The §5 journey passes on a dedicated emulator against a dedicated Synapse in an E2EE room. It uses
    real encrypted images, revisions/status edits and action round trips, with evidence under
    `build/e2e/interactive-ui/`.
14. **Quality gates.** The commands in §4 pass: targeted and module unit tests, Konsist, detekt, ktlint, lint, Kotlin
    compilation of both flavours and `:app:assembleGplayDebug`. Screenshot baselines are **not** recorded locally (CI
    records them with the `Record-Screenshots` label).

---

## 2. Design

### 2.1 Module boundaries and data flow

```
:libraries:interactiveui:core   (android-library, no Compose; kotlinx-serialization-json, okhttp)
    protocol/  constants, wire model, schema interpreter, relational validation, parser
    state/     state rules (kind, accepts, initial, merge, conditions, submission)
    action/    action content builder, nonce, JS number formatting
    session/   InteractiveUiSession state machine (local state, drafts, D6 lifecycle, revisions, persistence envelope)
    resources: interactive-ui/interactive-ui-v1.schema.json (vendored); test resources: fixtures (vendored)

:libraries:interactiveui:ui     (android-compose-library; depends on core, designsystem, matrixmedia:api, htmlrenderer:api)
    model/     InteractiveUiViewState, InteractiveUiRenderNode, InteractiveUiViewEvent
    graph/     layered layout (barycentre), fixed geometry, InteractiveUiGraphView
    chart/     InteractiveUiChartModel, niceTicks, InteractiveUiChartView (Canvas + data table)
    table/     InteractiveUiTableView (sort, show-all)
    InteractiveUiView + controls/carousel/link/status, previews, temporary.xml strings
    InteractiveUiRenderTreeBuilder (session state → render tree)

:libraries:matrix:{api,impl,test}   JoinedRoom.sendRaw
:libraries:featureflag:api          FeatureFlags.InteractiveUi
:features:messages:impl             content type, factory hook, session registry, state store, presenter, DI, view wiring
tools/interactive-ui/fixture-bot    e2e sender (Node), .maestro/tests/interactiveUi/*.yaml
```

```
Rust SDK timeline diff ─▶ TimelineItemsFactory (cached per item)
  └ TimelineItemContentFactory.create(eventTimelineItem)
      └ base TimelineItemTextContent (m.text) ─┬ flag off / local echo / no key ──────────────▶ TimelineItemTextContent
                                               └ TimelineItemContentInteractiveUiFactory
                                                   debugInfo(): latest_edit m.new_content ?: original content
                                                   InteractiveUiParser.parse ─▶ Valid(payload) | Invalid(code)
                                                   rich_text → DocumentNode (sanitised, links unwrapped)
                                                   images → MediaSource(mxc | {"file": EncryptedFile})
                                                   ─▶ TimelineItemInteractiveUiContent(fallback, model, context)
TimelineItemEventContentView ─▶ TimelineItemPresenterFactories.rememberPresenter(content)
  └ InteractiveUiPresenter ─▶ InteractiveUiSessionRegistry (room scope).sessionFor(content)
        InteractiveUiSession (one per event id) ── state ─▶ InteractiveUiRenderTreeBuilder ─▶ InteractiveUiViewState
        local events: no network                         ─▶ InteractiveUiView (stateless Compose)
        submit: validate → build content → JoinedRoom.sendRaw (room coroutine scope) → D6 phases
        persistence: debounced envelope → InteractiveUiStateStore (session DataStore)
        open_url: dialog in view → confirm → onLinkClick(Link(url, url))
```

Trust boundary: the payload is hostile data. The view model (session) is the only code that can send. It sends only
the action content of the Web plan §2.3, into the same room or thread, after an explicit tap. Nothing in the payload
can execute code, reach the Matrix client, or open a URL without the dialog.

### 2.2 Wire contract (unchanged from Web)

No new keys, namespaces, versions or fields. The constants are `net.15cm.matrix.interactive_ui`,
`net.15cm.matrix.interactive_ui.action` and version `1`. The payload, node catalog, limits, error codes and action
message are exactly Web plan §2.3 and §2.4 plus the resolutions in `fixtures/README.md`. Android reads the payload from
`latest_edit_json.content["m.new_content"]` when an edit exists, otherwise from `original_json.content` (Web plan §2.7).
Only events whose raw `type` is `m.room.message` and whose SDK message type is `TextMessageType` are candidates.

### 2.3 `:libraries:interactiveui:core` (exact signatures)

Package root `io.element.android.libraries.interactiveui.core`. All public functions are pure or confined to the
session. None of them throws for untrusted input.

```kotlin
// protocol/InteractiveUiConstants.kt
object InteractiveUiConstants {
    const val PAYLOAD_KEY: String = "net.15cm.matrix.interactive_ui"
    const val ACTION_KEY: String = "net.15cm.matrix.interactive_ui.action"
    const val VERSION: Int = 1
    const val CLIENT_MAX_PAYLOAD_BYTES: Int = 60_000
    const val CONFIRMATION_OVERDUE_MILLIS: Long = 8_000L
    const val PERSIST_DEBOUNCE_MILLIS: Long = 200L
}

// protocol/InteractiveUiValue.kt — JS `string | number | boolean | null | string[]`
sealed interface InteractiveUiValue {
    data class Text(val value: String) : InteractiveUiValue
    /** Equality follows JS `===`: 0.0 == -0.0. Values are always finite. */
    class Number(val value: Double) : InteractiveUiValue { /* equals/hashCode on value + 0.0 */ }
    data class Bool(val value: Boolean) : InteractiveUiValue
    data class TextList(val values: List<String>) : InteractiveUiValue
    data object Null : InteractiveUiValue
}
fun InteractiveUiValue.toJsonElement(): JsonElement
fun JsonElement.toInteractiveUiValueOrNull(): InteractiveUiValue?

// protocol/InteractiveUiError.kt
enum class InteractiveUiErrorCode(val wire: String) {
    NotObject("not_object"), BadVersion("bad_version"), TooLarge("too_large"), MissingField("missing_field"),
    BadType("bad_type"), BadId("bad_id"), DuplicateId("duplicate_id"), UnknownRef("unknown_ref"), Cycle("cycle"),
    TooDeep("too_deep"), TooMany("too_many"), TooLong("too_long"), BadValue("bad_value"), BadUrl("bad_url"),
    TypeMismatch("type_mismatch");
    companion object { fun fromWire(wire: String): InteractiveUiErrorCode? }
}
data class InteractiveUiError(val path: String, val code: InteractiveUiErrorCode)

// protocol/InteractiveUiPayload.kt
enum class InteractiveUiStatus { Active, Submitted, Expired, Superseded }
enum class InteractiveUiTone { Neutral, Info, Success, Warning, Critical }

data class InteractiveUiPayload(
    val uiId: String,
    val revision: Long,
    val status: InteractiveUiStatus,
    val statusText: String?,
    val title: String?,
    /** Wire order; values already trimmed and validated. */
    val state: Map<String, InteractiveUiValue>,
    val root: String,
    /** Only nodes reachable from [root], in wire order. */
    val nodes: List<InteractiveUiNode>,
) {
    val nodesById: Map<String, InteractiveUiNode>
}

data class InteractiveUiCondition(val stateKey: String, val comparison: Comparison, val negate: Boolean) {
    sealed interface Comparison {
        data class EqualTo(val value: InteractiveUiValue) : Comparison
        data class OneOf(val values: List<InteractiveUiValue>) : Comparison
    }
}

sealed interface InteractiveUiAction {
    data class SetState(val key: String, val value: InteractiveUiValue) : InteractiveUiAction
    data class ToggleState(val key: String) : InteractiveUiAction
    /** [singleUse] already defaulted to false (D5). */
    data class Submit(val actionId: String, val include: List<String>, val singleUse: Boolean) : InteractiveUiAction
    data class OpenUrl(val url: String) : InteractiveUiAction
}

data class InteractiveUiOption(val value: String, val label: String)
data class InteractiveUiImage(
    val alt: String,
    val mxcUrl: String?,
    /** The validated Matrix EncryptedFile object, verbatim. Exactly one of [mxcUrl]/[encryptedFile] is set. */
    val encryptedFile: JsonObject?,
    val mimetype: String,
    val width: Int?,
    val height: Int?,
)

sealed interface InteractiveUiNode {
    val id: String
    val visibleWhen: InteractiveUiCondition?
    val fallback: String?

    enum class Direction { Vertical, Horizontal }
    enum class Gap { None, Sm, Md, Lg }
    data class Stack(override val id: String, override val visibleWhen: InteractiveUiCondition?, override val fallback: String?,
        val children: List<String>, val direction: Direction, val gap: Gap /* default Md */) : InteractiveUiNode
    data class Card(/* base */, val title: String?, val children: List<String>) : InteractiveUiNode
    data class Tabs(/* base */, val bind: String, val tabs: List<Tab>) : InteractiveUiNode {
        data class Tab(val value: String, val label: String, val child: String)
    }
    data class Collapsible(/* base */, val label: String, val children: List<String>, val bind: String?) : InteractiveUiNode
    data class Divider(/* base */) : InteractiveUiNode
    data class Heading(/* base */, val text: String, val level: Int /* 1..3 */) : InteractiveUiNode
    enum class TextTone { Default, Muted, Emphasis }
    enum class TextSize { Body, Caption }
    data class Text(/* base */, val text: String, val tone: TextTone, val size: TextSize) : InteractiveUiNode
    data class RichText(/* base */, val text: String, val html: String?) : InteractiveUiNode
    data class Badge(/* base */, val text: String, val tone: InteractiveUiTone) : InteractiveUiNode
    data class KeyValue(/* base */, val items: List<Pair<String, String>>) : InteractiveUiNode
    data class ItemList(/* base */, val items: List<Item>, val ordered: Boolean) : InteractiveUiNode {
        data class Item(val text: String, val secondary: String?)
    }
    data class Image(/* base */, val image: InteractiveUiImage) : InteractiveUiNode
    data class Carousel(/* base */, val label: String, val items: List<Item>) : InteractiveUiNode {
        data class Item(val id: String, val image: InteractiveUiImage, val title: String, val subtitle: String?,
            val meta: List<String>, val badge: Pair<String, InteractiveUiTone>?, val action: InteractiveUiAction?)
    }
    enum class LinkKind { Source, External }
    data class Link(/* base */, val label: String, val url: String, val kind: LinkKind /* default External */) : InteractiveUiNode
    data class Table(/* base */, val caption: String?, val columns: List<Column>, val rows: List<Map<String, InteractiveUiValue>>,
        val sortable: Boolean) : InteractiveUiNode {
        data class Column(val key: String, val label: String, val numeric: Boolean, val alignEnd: Boolean)
    }
    sealed interface ChartData {
        enum class Kind { Bar, Line, Pie }
        data class Categorical(val kind: Kind, val categories: List<String>, val series: List<Series>) : ChartData
        data class Scatter(val series: List<ScatterSeries>) : ChartData
        data class Series(val name: String, val values: List<Double>)
        data class ScatterSeries(val name: String, val points: List<Point>)
        data class Point(val x: Double, val y: Double, val label: String?)
    }
    data class Chart(/* base */, val title: String?, val xLabel: String?, val yLabel: String?, val unit: String?,
        val data: ChartData) : InteractiveUiNode
    data class Graph(/* base */, val topToBottom: Boolean, val layers: List<String>, val nodes: List<GraphNode>,
        val edges: List<GraphEdge>, val bindSelected: String?) : InteractiveUiNode {
        data class GraphNode(val id: String, val label: String, val detail: String?, val layer: Int, val tone: InteractiveUiTone)
        data class GraphEdge(val from: String, val to: String, val label: String?, val dashed: Boolean)
    }
    enum class ButtonVariant { Primary, Secondary, Danger }
    data class Button(/* base */, val label: String, val variant: ButtonVariant /* default Secondary */,
        val action: InteractiveUiAction) : InteractiveUiNode
    data class Select(/* base */, val label: String, val bind: String, val options: List<InteractiveUiOption>) : InteractiveUiNode
    data class Choice(/* base */, val label: String, val bind: String, val options: List<InteractiveUiOption>,
        val multiple: Boolean) : InteractiveUiNode
    data class Toggle(/* base */, val label: String, val bind: String) : InteractiveUiNode
    data class TextInput(/* base */, val label: String, val bind: String, val multiline: Boolean, val placeholder: String?,
        val maxLength: Int, val required: Boolean) : InteractiveUiNode
    data class NumberInput(/* base */, val label: String, val bind: String, val min: Double?, val max: Double?,
        val step: Double?, val slider: Boolean) : InteractiveUiNode
    /** [singleUse] already defaulted to true (D5). */
    data class Form(/* base */, val children: List<String>, val submitLabel: String, val actionId: String,
        val singleUse: Boolean) : InteractiveUiNode
    data class Unknown(/* base */) : InteractiveUiNode
}
// "/* base */" stands for the three overridden properties id, visibleWhen, fallback, declared first in that order.

// protocol/InteractiveUiParser.kt
sealed interface InteractiveUiParseResult {
    data class Valid(val payload: InteractiveUiPayload) : InteractiveUiParseResult
    data class Invalid(val error: InteractiveUiError) : InteractiveUiParseResult
}
object InteractiveUiParser {
    /** Returns null when [content] has no PAYLOAD_KEY (not an error). Never throws. Logs only path+code via Timber.w. */
    fun parse(content: JsonObject): InteractiveUiParseResult?

    /**
     * Picks `m.new_content` of [latestEditJson] when present, otherwise `content` of [originalJson], and parses it.
     * Returns null for absent or unreadable JSON, a non-`m.room.message` event, or content without the key.
     * Raw strings over 262,144 chars return Invalid("", too_large) without being parsed.
     */
    fun parseEvent(originalJson: String?, latestEditJson: String?): InteractiveUiParseResult?
}

// protocol/InteractiveUiSchema.kt (internal)
internal class InteractiveUiSchema private constructor(private val root: JsonObject) {
    /** Port of Web `read()`: returns the cleaned value (trimmed strings, unknown properties dropped) or throws. */
    fun read(value: JsonElement): JsonElement
    companion object { val V1: InteractiveUiSchema /* lazy, from classpath resource interactive-ui/interactive-ui-v1.schema.json */ }
}
internal class InteractiveUiValidationException(val error: InteractiveUiError) :
    RuntimeException(null, null, false, false)
// protocol/JsonSafety.kt (internal): port of copyJson(): byte/visit/depth caps, finite numbers, JS key order.
// protocol/InteractiveUiRelations.kt (internal): port of validateRelations(); wire indices in paths; returns reachable ids.
// protocol/JsSemantics.kt (internal): jsTrim, codePointLength, JS_WHITESPACE_CLASS, isSafeInteger, jsKeyOrder, jsNumberToString.

// state/InteractiveUiStateRules.kt
enum class InteractiveUiStateKind { Text, Number, Bool, TextList, Null }
sealed interface InteractiveUiSubmission {
    data class Valid(val actionId: String, val values: Map<String, InteractiveUiValue>) : InteractiveUiSubmission
    data class Invalid(val invalidKeys: Set<String>) : InteractiveUiSubmission
}
object InteractiveUiStateRules {
    fun stateKind(payload: InteractiveUiPayload, key: String): InteractiveUiStateKind
    fun acceptsStateValue(payload: InteractiveUiPayload, key: String, value: InteractiveUiValue?): Boolean
    fun initialState(payload: InteractiveUiPayload): Map<String, InteractiveUiValue>
    /** [saved] is a `{ui_id, revision, state}` envelope or a raw state map; anything else yields initialState. */
    fun mergeSavedState(payload: InteractiveUiPayload, saved: JsonElement?): Map<String, InteractiveUiValue>
    fun evaluateCondition(condition: InteractiveUiCondition, state: Map<String, InteractiveUiValue>): Boolean
    fun validateSubmission(
        payload: InteractiveUiPayload,
        nodeId: String,
        state: Map<String, InteractiveUiValue>,
        carouselItemId: String? = null,
    ): InteractiveUiSubmission
    fun childIds(node: InteractiveUiNode): List<String>
}

// action/InteractiveUiActionContentBuilder.kt
data class InteractiveUiEventContext(
    val eventId: String,      // original UI event id, never an edit id
    val senderId: String,
    val threadRootId: String?,
)
object InteractiveUiActionContentBuilder {
    /** Byte-for-byte semantic equivalent of Web buildActionContent (body ≤ 1000 code points). */
    fun build(
        context: InteractiveUiEventContext,
        payload: InteractiveUiPayload,
        actionId: String,
        values: Map<String, InteractiveUiValue>,
        triggerLabel: String,
        nonce: String,
    ): JsonObject
}
object InteractiveUiNonce {
    /** 24 bytes from SecureRandom, base64url without padding (32 chars). */
    fun generate(random: SecureRandom = SecureRandom()): String
}

// session/InteractiveUiPhase.kt (value types shared by session and ui)
enum class InteractiveUiPhase { Active, Sending, AwaitingConfirmation, Submitted, Expired, Superseded }
sealed interface InteractiveUiNotice {
    data class PayloadText(val text: String) : InteractiveUiNotice
    data object SendFailed : InteractiveUiNotice
    data object WaitingForSender : InteractiveUiNotice
}
data class InteractiveUiTableSort(val columnKey: String, val descending: Boolean)
sealed interface InteractiveUiUrlRequest {
    data class Confirming(val url: String) : InteractiveUiUrlRequest
    data class Approved(val url: String) : InteractiveUiUrlRequest
}

// session/InteractiveUiSession.kt
data class InteractiveUiSessionState(
    val payload: InteractiveUiPayload,
    val values: Map<String, InteractiveUiValue>,
    /** Invalid or partial number drafts by state key; null = empty/partial text. Never persisted or sent. */
    val numberDrafts: Map<String, Double?>,
    val invalidKeys: Set<String>,
    val collapsibleOpen: Map<String, Boolean>,      // unbound collapsibles, by node id
    val graphSelection: Map<String, String?>,       // unbound graphs, by node id
    val graphListMode: Set<String>,
    val chartDataMode: Set<String>,
    val tableSort: Map<String, InteractiveUiTableSort>,
    val carouselIndex: Map<String, Int>,
    val phase: InteractiveUiPhase,
    val notice: InteractiveUiNotice?,
    val confirmationOverdue: Boolean,
    val showAsText: Boolean,
    val urlRequest: InteractiveUiUrlRequest?,
    /** Node id whose action is in flight (button/form/carousel) for the pending indicator. */
    val pendingNodeId: String?,
)

class InteractiveUiSession(
    private val context: InteractiveUiEventContext,
    initialPayload: InteractiveUiPayload,
    savedState: JsonElement?,
    private val scope: CoroutineScope,
    private val sendAction: suspend (content: JsonObject) -> Result<Unit>,
    private val persistState: suspend (envelope: JsonObject) -> Unit,
    private val nonce: () -> String = { InteractiveUiNonce.generate() },
) {
    val state: StateFlow<InteractiveUiSessionState>

    fun onRevision(payload: InteractiveUiPayload)          // ignores revision <= current (R22)
    fun applySavedState(savedState: JsonElement)           // only while no user change happened yet
    fun setValue(nodeId: String, value: InteractiveUiValue)
    fun selectGraphNode(nodeId: String, graphNodeId: String?)
    fun setGraphListMode(nodeId: String, asList: Boolean)
    fun setChartDataMode(nodeId: String, showData: Boolean)
    fun sortTable(nodeId: String, columnKey: String)
    fun setShowAsText(showAsText: Boolean)
    fun setCarouselIndex(nodeId: String, index: Int)
    fun activate(nodeId: String)
    fun activateCarouselItem(nodeId: String, itemId: String)
    fun submitForm(nodeId: String)
    fun openLink(nodeId: String)
    fun confirmOpenUrl()                                   // Confirming → Approved, only if still active & same revision
    fun clearUrlRequest()
    fun retryAfterOverdue()
    fun dispose()                                          // cancels timers, flushes pending persistence
}
```

Session behaviour is a line-by-line port of Web `InteractiveUiBodyViewModel`, minus media and Matrix specifics:

- `active` means: not disposed, not sending, and phase `Active`. Every mutator is a no-op otherwise, except
  `setShowAsText`, `retryAfterOverdue` and the URL request handling.
- `setValue` covers bound controls, tabs and collapsibles. A non-slider `number_input` with `Null`, or with a number the
  bound rules reject, goes to `numberDrafts`. A valid value clears that key's draft and invalid flag.
- Button actions are `set_state`, `toggle_state` (boolean keys), `open_url` (→ `urlRequest = Confirming`) and `submit`.
- Submission validates with `validateSubmission` over `values + numberDrafts`, then blocks on drafts. It builds the
  content with a fresh nonce, then `phase = Sending` until `sendAction` returns. On success: if no newer revision
  arrived and the action is single-use, `AwaitingConfirmation` plus an 8 s `delay` that sets `confirmationOverdue` and
  `WaitingForSender`; otherwise the payload's status. On failure: payload status plus `SendFailed` (only when the
  revision is unchanged).
- `onRevision` merges state with `mergeSavedState(new, envelope(old))` and clears drafts, invalid keys and view-only
  maps (Web parity). It cancels the timer and adopts the new status. If a send is still in flight and the new status is
  `active`, it stays `Sending`.
- Persistence: every state change schedules `persistState(envelope)` after 200 ms (debounced); `dispose()` flushes.
  Only `values` are persisted, never drafts.
- No Timber logging of values, labels or URLs; at most `Timber.w("interactive_ui send failed")` with the exception
  class name.

### 2.4 `:libraries:interactiveui:ui` (exact signatures)

Package root `io.element.android.libraries.interactiveui.ui`. The view is stateless, and all state is in
`InteractiveUiViewState`.

```kotlin
// model/InteractiveUiViewEvent.kt
sealed interface InteractiveUiViewEvent {
    data class SetValue(val nodeId: String, val value: InteractiveUiValue) : InteractiveUiViewEvent
    data class Activate(val nodeId: String) : InteractiveUiViewEvent
    data class SubmitForm(val nodeId: String) : InteractiveUiViewEvent
    data class SelectGraphNode(val nodeId: String, val graphNodeId: String?) : InteractiveUiViewEvent
    data class SetGraphListMode(val nodeId: String, val asList: Boolean) : InteractiveUiViewEvent
    data class SetChartDataMode(val nodeId: String, val showData: Boolean) : InteractiveUiViewEvent
    data class SortTable(val nodeId: String, val columnKey: String) : InteractiveUiViewEvent
    data class SetShowAsText(val showAsText: Boolean) : InteractiveUiViewEvent
    data class SetCarouselIndex(val nodeId: String, val index: Int) : InteractiveUiViewEvent
    data class ActivateCarouselItem(val nodeId: String, val itemId: String) : InteractiveUiViewEvent
    data class OpenLink(val nodeId: String) : InteractiveUiViewEvent
    data object ConfirmOpenUrl : InteractiveUiViewEvent
    data object ClearUrlRequest : InteractiveUiViewEvent
    data object RetryAfterOverdue : InteractiveUiViewEvent
}

// model/InteractiveUiViewState.kt
@Immutable
data class InteractiveUiViewState(
    val title: String?,
    val phase: InteractiveUiPhase,
    val notice: InteractiveUiNotice?,
    val senderDisplayName: String,
    val confirmationOverdue: Boolean,
    val showAsText: Boolean,
    val urlRequest: InteractiveUiUrlRequest?,
    val root: InteractiveUiRenderNode,
    val eventSink: (InteractiveUiViewEvent) -> Unit,
)

// model/InteractiveUiRenderNode.kt — resolved, visible-only tree (mirrors Web InteractiveUiRenderNode)
enum class InteractiveUiBadgeTone { Neutral, Info, Success, Warning, Critical }
@Immutable
sealed interface InteractiveUiRenderNode {
    val id: String
    data class Stack(override val id: String, val horizontal: Boolean, val gap: Dp, val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Card(override val id: String, val title: String?, val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Tabs(override val id: String, val selected: String, val tabs: ImmutableList<Tab>, val enabled: Boolean) : InteractiveUiRenderNode {
        data class Tab(val value: String, val label: String, val content: InteractiveUiRenderNode)
    }
    data class Collapsible(override val id: String, val label: String, val open: Boolean, val enabled: Boolean,
        val children: ImmutableList<InteractiveUiRenderNode>) : InteractiveUiRenderNode
    data class Divider(override val id: String) : InteractiveUiRenderNode
    data class Heading(override val id: String, val text: String, val level: Int) : InteractiveUiRenderNode
    data class Text(override val id: String, val text: String, val tone: InteractiveUiNode.TextTone, val caption: Boolean) : InteractiveUiRenderNode
    data class RichText(override val id: String, val text: String, val document: DocumentNode?) : InteractiveUiRenderNode
    data class Badge(override val id: String, val text: String, val tone: InteractiveUiBadgeTone) : InteractiveUiRenderNode
    data class KeyValue(override val id: String, val items: ImmutableList<Pair<String, String>>) : InteractiveUiRenderNode
    data class ItemList(override val id: String, val ordered: Boolean, val items: ImmutableList<Pair<String, String?>>) : InteractiveUiRenderNode
    data class Image(override val id: String, val alt: String, val source: MediaSource?, val mimeType: String,
        val aspectRatio: Float?, val hidden: Boolean) : InteractiveUiRenderNode
    data class Table(override val id: String, val caption: String?, val columns: ImmutableList<InteractiveUiTableColumn>,
        val rows: ImmutableList<ImmutableList<InteractiveUiCell>>, val sort: InteractiveUiTableSort?, val sortable: Boolean,
        val enabled: Boolean) : InteractiveUiRenderNode
    data class Chart(override val id: String, val chart: InteractiveUiChartModel, val showData: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Graph(override val id: String, val layout: InteractiveUiGraphLayout, val selectedId: String?,
        val showAsList: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Button(override val id: String, val label: String, val variant: InteractiveUiNode.ButtonVariant,
        val enabled: Boolean, val pending: Boolean, val external: Boolean) : InteractiveUiRenderNode
    data class Select(override val id: String, val label: String, val selected: String?,
        val options: ImmutableList<InteractiveUiOption>, val enabled: Boolean) : InteractiveUiRenderNode
    data class Choice(override val id: String, val label: String, val selected: ImmutableList<String>, val multiple: Boolean,
        val options: ImmutableList<InteractiveUiOption>, val enabled: Boolean) : InteractiveUiRenderNode
    data class Toggle(override val id: String, val label: String, val checked: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class TextInput(override val id: String, val label: String, val value: String, val multiline: Boolean,
        val placeholder: String?, val maxLength: Int, val required: Boolean, val invalid: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class NumberInput(override val id: String, val label: String, val value: Double?, val min: Double?, val max: Double?,
        val step: Double?, val slider: Boolean, val invalid: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Form(override val id: String, val submitLabel: String, val children: ImmutableList<InteractiveUiRenderNode>,
        val enabled: Boolean, val pending: Boolean) : InteractiveUiRenderNode
    data class Carousel(override val id: String, val label: String, val index: Int, val items: ImmutableList<Item>,
        val enabled: Boolean) : InteractiveUiRenderNode {
        data class Item(val id: String, val source: MediaSource?, val alt: String, val title: String, val subtitle: String?,
            val meta: ImmutableList<String>, val badge: Pair<String, InteractiveUiBadgeTone>?, val actionLabel: String?,
            val external: Boolean, val hidden: Boolean)
    }
    data class Link(override val id: String, val label: String, val host: String, val source: Boolean, val enabled: Boolean) : InteractiveUiRenderNode
    data class Unknown(override val id: String, val fallback: String?) : InteractiveUiRenderNode
}

// table/InteractiveUiTableTypes.kt
data class InteractiveUiTableColumn(val key: String, val label: String, val numeric: Boolean, val alignEnd: Boolean)
sealed interface InteractiveUiCell {
    data class Text(val value: String) : InteractiveUiCell
    class Number(val value: Double) : InteractiveUiCell   // JS-style display via jsNumberToString-equivalent formatter
    data object Empty : InteractiveUiCell
}
@Composable fun InteractiveUiTableView(
    caption: String?, columns: ImmutableList<InteractiveUiTableColumn>, rows: ImmutableList<ImmutableList<InteractiveUiCell>>,
    sort: InteractiveUiTableSort?, sortable: Boolean, enabled: Boolean, onSort: (columnKey: String) -> Unit,
    modifier: Modifier = Modifier,
)
/** Stable sort: nulls first, numbers numerically, text by Collator(root) then original order (Web parity). */
fun sortedRowIndices(rows: List<List<InteractiveUiCell>>, columnIndex: Int, numeric: Boolean, descending: Boolean): List<Int>

// chart/InteractiveUiChartModel.kt
sealed interface InteractiveUiChartModel {
    val title: String?
    val unit: String?
    data class Categorical(override val title: String?, override val unit: String?, val line: Boolean, val xLabel: String?,
        val yLabel: String?, val categories: ImmutableList<String>, val series: ImmutableList<Series>) : InteractiveUiChartModel
    data class Pie(override val title: String?, override val unit: String?, val categories: ImmutableList<String>,
        val values: ImmutableList<Double>) : InteractiveUiChartModel
    data class Scatter(override val title: String?, override val unit: String?, val xLabel: String?, val yLabel: String?,
        val series: ImmutableList<ScatterSeries>) : InteractiveUiChartModel
    data class Series(val name: String, val values: ImmutableList<Double>)
    data class ScatterSeries(val name: String, val points: ImmutableList<Triple<Double, Double, String?>>)
}
/** Port of Web niceTicks (1/2/5 steps, bounded, finite). */
fun niceTicks(min: Double, max: Double, maxTicks: Int): List<Double>
@Composable fun InteractiveUiChartView(chart: InteractiveUiChartModel, showData: Boolean, enabled: Boolean,
    onToggleData: (Boolean) -> Unit, modifier: Modifier = Modifier)

// graph/InteractiveUiGraphLayout.kt
data class InteractiveUiGraphInput(val topToBottom: Boolean, val layers: List<String>, val nodes: List<Node>, val edges: List<Edge>) {
    data class Node(val id: String, val label: String, val detail: String?, val layer: Int, val tone: InteractiveUiBadgeTone)
    data class Edge(val from: String, val to: String, val label: String?, val dashed: Boolean)
}
@Immutable
data class InteractiveUiGraphLayout(val topToBottom: Boolean, val layers: ImmutableList<String>,
    val nodes: ImmutableList<Node>, val edges: ImmutableList<InteractiveUiGraphInput.Edge>) {
    data class Node(val id: String, val label: String, val detail: String?, val tone: InteractiveUiBadgeTone, val layer: Int, val order: Int)
}
/** Port of Web layoutLayeredGraph: input order, four stable barycentre sweeps (down, up, down, up). */
fun layoutLayeredGraph(input: InteractiveUiGraphInput): InteractiveUiGraphLayout
/** Port of Web graphGeometry in dp: node 168×56, layer gap 72, node gap 16, margin 48, labelled outside lanes. */
data class InteractiveUiGraphGeometry(val width: Float, val height: Float, val boxes: Map<String, Rect>,
    val headings: List<Pair<Offset, String>>, val paths: List<EdgePath>) {
    data class EdgePath(val segments: List<CubicSegment>, val labelBox: Rect)
    data class CubicSegment(val start: Offset, val c1: Offset, val c2: Offset, val end: Offset)
}
fun graphGeometry(layout: InteractiveUiGraphLayout): InteractiveUiGraphGeometry
@Composable fun InteractiveUiGraphView(layout: InteractiveUiGraphLayout, selectedId: String?, showAsList: Boolean,
    enabled: Boolean, onSelect: (String?) -> Unit, onToggleList: (Boolean) -> Unit, modifier: Modifier = Modifier)

// InteractiveUiView.kt
@Composable
fun InteractiveUiView(
    state: InteractiveUiViewState,
    onOpenUrl: (url: String) -> Unit,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
)

// InteractiveUiRenderTreeBuilder.kt (owned by T6; pure, unit-tested)
object InteractiveUiRenderTreeBuilder {
    fun build(
        session: InteractiveUiSessionState,
        richText: ImmutableMap<String, DocumentNode>,      // by rich_text node id
        media: ImmutableMap<String, MediaSource>,          // by image node id or "<carouselId>/<itemId>" (Web keys)
        hideMedia: Boolean,
        senderDisplayName: String,
        eventSink: (InteractiveUiViewEvent) -> Unit,
    ): InteractiveUiViewState
}
```

Rendering rules (Compound tokens and `libraries/designsystem` components only):

| Node                | Compose rendering                                                                                                                                                                                                                                                               |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| root                | Bordered card (`borderInteractiveSecondary`, 12 dp radius, `bgCanvasDefault`), optional title (`heading()`), status line (`liveRegion = Polite`) with "Try again" when overdue, "Show as text" toggle (`Role.Switch`), then the tree or `fallback()`. URL dialog = `ConfirmationDialog` with the full URL. |
| stack               | Vertical `Column` with gap none/4/8/16 dp. Horizontal `FlowRow` whose children have `weight(1f)` and `widthIn(min = 200.dp)`, so they wrap when narrow.                                                                                                                       |
| card                | Inner surface (`bgSubtleSecondary`), title as `heading()`.                                                                                                                                                                                                                       |
| tabs                | Scrollable row of selectable chips (`Role.Tab`, `selected`), with panel content below. Disabled when inactive.                                                                                                                                                                    |
| collapsible         | Full-width text button with expand icon and `stateDescription` expanded/collapsed.                                                                                                                                                                                               |
| heading/text/badge/key_value/list/divider | Compound typography (`fontHeadingMdBold`, `fontHeadingSmMedium`, `fontBodyLgRegular`, `fontBodySmRegular`), muted = `textSecondary`, emphasis = medium weight. `Badge` from designsystem with tone colours. Key/value is a wrapping two-line layout. `•` or `1.` list markers. |
| rich_text           | `HtmlMessageContent(document)` with no-op link and mention callbacks; falls back to `text`.                                                                                                                                                                                       |
| image               | `AsyncImage(MediaRequestData(source, Kind.Content))`, `contentDescription = alt`, aspect from width/height (default 16:10), max height 320 dp. When media is hidden: alt text plus "Show".                                                                                      |
| table/chart/graph   | §T3. Only these, plus carousels, scroll horizontally (`horizontalScroll`) inside the card.                                                                                                                                                                                      |
| button              | Designsystem `Button`/`OutlinedButton` (danger → destructive), full width when narrow, a progress indicator when `pending`, "(External link)" suffix for `open_url`.                                                                                                             |
| select              | Exposed dropdown: read-only `TextField` plus designsystem `DropdownMenu`, "Choose an option" placeholder.                                                                                                                                                                       |
| choice              | `RadioButton` or `Checkbox` rows in a `selectableGroup()`, each row toggleable with a 48 dp target.                                                                                                                                                                              |
| toggle              | `Switch` row (`toggleable`, `Role.Switch`).                                                                                                                                                                                                                                      |
| text_input          | Designsystem `TextField` (label, placeholder, `maxLength` enforced in `onValueChange`, multiline lines 3–8, `ImeAction.Next`/`Default`), error via `isError` + supporting text + `semantics { error() }`.                                                                     |
| number_input        | `TextField` with `KeyboardType.Decimal` keeping raw text (R20), or designsystem `Slider` (`steps` derived from `step` when it gives ≤ 100 steps).                                                                                                                              |
| form                | Children plus a primary submit button; `pending` indicator; never submits from the IME (R10).                                                                                                                                                                                    |
| carousel            | One item: a single card. Otherwise `HorizontalPager` (page width `min(available × 0.86, 300.dp)`, 12 dp spacing, snap) with Previous/Next buttons and "Item n of m" (`liveRegion`). The settled page is pushed to the session; session changes animate the pager. Item = image, title, subtitle, ≤ 3 meta lines, badge, one action button. |
| link                | Chip-style outlined button "label · host (Source/External link)".                                                                                                                                                                                                               |
| unknown             | `fallback` or "This content cannot be displayed." in muted text.                                                                                                                                                                                                                |

Strings go in `libraries/interactiveui/ui/src/main/res/values/temporary.xml` as `screen_interactive_ui_*`
(`carousel`, `category`, `chart_bar|line|pie|scatter`, `chart_summary` with `%1$s %2$s %3$d`, `choose_option`,
`confirmation_overdue`, `waiting_for_sender` with `%1$s`, `data_table`, `external_link`, `graph`, `invalid_value`,
`item_position` with `%1$d %2$d`, `legend`, `pie_negative_values`, `pie_zero_total`, `relationships_list`, `series`,
`show_as_text`, `show_all_rows` with `%1$d`, `source`, `status_*`, `tabs`, `unknown_content`, `value`, `x_axis`,
`y_axis`, `open_link_title`, `send_failed`, `invalid_content`) plus `a11y_interactive_ui_*` (`graph_node` with
`%1$s %2$s`, `selected`, `sorted_ascending`, `sorted_descending`, `expanded`, `collapsed`). Existing `CommonStrings`
(`action_continue`, `action_cancel`, `action_next`, `action_previous`, `action_try_again`, `action_show`) are reused
when present. Nothing goes in `localazy.xml`.

### 2.5 Platform hooks (exact signatures)

```kotlin
// libraries/matrix/api/.../room/JoinedRoom.kt
/**
 * Sends a raw, client-built event of [eventType] (encrypted when the room is) and returns its event id once the
 * homeserver accepts it. Bypasses the send queue, so there is no local echo.
 */
suspend fun sendRaw(eventType: String, content: String): Result<EventId>

// libraries/matrix/impl/.../room/JoinedRustRoom.kt
override suspend fun sendRaw(eventType: String, content: String): Result<EventId> = withContext(roomDispatcher) {
    runCatchingExceptions { EventId(innerRoom.sendRaw(eventType, content, null)) }
}

// libraries/matrix/test/.../room/FakeJoinedRoom.kt
private val sendRawResult: (String, String) -> Result<EventId> = { _, _ -> lambdaError() }
override suspend fun sendRaw(eventType: String, content: String): Result<EventId> = simulateLongTask { sendRawResult(eventType, content) }

// libraries/featureflag/api/.../FeatureFlags.kt
InteractiveUi(
    key = "feature.interactive_ui",
    title = "Interactive messages",
    description = "Render interactive cards, forms, charts and knowledge maps sent with net.15cm.matrix.interactive_ui.",
    defaultValue = { false },
    isFinished = false,
    isInLabs = true,
),
```

### 2.6 `:features:messages:impl` integration (exact signatures)

```kotlin
// timeline/model/event/TimelineItemInteractiveUiContent.kt (must be in this package: sealed hierarchy)
sealed interface InteractiveUiTimelineModel {
    data class Valid(
        val payload: InteractiveUiPayload,
        val richText: ImmutableMap<String, DocumentNode>,
        val media: ImmutableMap<String, MediaSource>,
    ) : InteractiveUiTimelineModel
    /** Only the code is kept; [showCaption] is false for bad_version. */
    data class Invalid(val code: InteractiveUiErrorCode) : InteractiveUiTimelineModel {
        val showCaption: Boolean get() = code != InteractiveUiErrorCode.BadVersion
    }
}
data class TimelineItemInteractiveUiContent(
    val eventId: EventId,
    val senderId: UserId,
    val senderDisplayName: String,
    val threadRootId: ThreadId?,
    val fallback: TimelineItemTextContent,
    val model: InteractiveUiTimelineModel,
) : TimelineItemTextBasedContent {
    override val type: String = "TimelineItemInteractiveUiContent"
    override val body: String get() = fallback.body
    override val htmlDocument: Document? get() = fallback.htmlDocument
    override val messageTree: DocumentNode? get() = fallback.messageTree
    override val formattedBody: CharSequence get() = fallback.formattedBody
    override val plainText: String get() = fallback.plainText
    override val isEdited: Boolean get() = fallback.isEdited
}

// timeline/factories/event/TimelineItemContentInteractiveUiFactory.kt
@Inject
class TimelineItemContentInteractiveUiFactory(
    private val featureFlagService: FeatureFlagService,
    private val permalinkParser: PermalinkParser,
    private val htmlMessageParser: HtmlMessageParser,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Returns null unless the flag is on, the item is remote m.text, and the latest content carries the key. Never throws. */
    suspend fun create(eventTimelineItem: EventTimelineItem, fallback: TimelineItemTextContent): TimelineItemInteractiveUiContent?
}
// TimelineItemContentFactory.create(eventTimelineItem, roomMembers): after the existing mapping,
//   (base as? TimelineItemTextContent)?.let { interactiveUiFactory.create(eventTimelineItem, it) } ?: base
// The other overload (replies, pinned, thread summaries) is unchanged, so those always show the fallback.

// interactiveui/InteractiveUiStateStore.kt
interface InteractiveUiStateStore {
    /** Synchronous read from the warm in-memory snapshot; null when absent or not yet loaded. */
    fun peek(roomId: RoomId, eventId: EventId): JsonElement?
    suspend fun load(roomId: RoomId, eventId: EventId): JsonElement?
    suspend fun save(roomId: RoomId, eventId: EventId, envelope: JsonObject)
}
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DataStoreInteractiveUiStateStore(
    client: MatrixClient,
    factory: PreferenceDataStoreFactory,
    sessionObserver: SessionObserver,
    private val systemClock: SystemClock,
    @SessionCoroutineScope sessionCoroutineScope: CoroutineScope,
) : InteractiveUiStateStore   // ≤ 200 entries, ≤ 64 KB each, cleared in onSessionDeleted (R9)

// interactiveui/InteractiveUiSessionRegistry.kt
@SingleIn(RoomScope::class)
@Inject
class InteractiveUiSessionRegistry(
    private val room: JoinedRoom,
    private val store: InteractiveUiStateStore,
) {
    /** Returns the session for the event, creating it or applying a newer revision. Main-thread only. */
    fun sessionFor(content: TimelineItemInteractiveUiContent, payload: InteractiveUiPayload): InteractiveUiSession
}
// Session wiring: scope = room.roomCoroutineScope; sendAction = { room.sendRaw("m.room.message", it.toString()).map { } };
// persistState = { store.save(room.roomId, eventId, it) }; saved = store.peek(...), else load() then applySavedState().

// interactiveui/InteractiveUiPresenter.kt
@AssistedInject
class InteractiveUiPresenter(
    @Assisted private val content: TimelineItemInteractiveUiContent,
    private val registry: InteractiveUiSessionRegistry,
) : Presenter<InteractiveUiViewState> {
    @AssistedFactory
    fun interface Factory : TimelineItemPresenterFactory<TimelineItemInteractiveUiContent, InteractiveUiViewState> {
        override fun create(content: TimelineItemInteractiveUiContent): InteractiveUiPresenter
    }
    @Composable override fun present(): InteractiveUiViewState
}
// Requires model is Valid (the view calls it only then). eventSink maps each InteractiveUiViewEvent to the session method.

// interactiveui/di/InteractiveUiPresenterBindingContainer.kt
@BindingContainer
@ContributesTo(RoomScope::class)
interface InteractiveUiPresenterBindingContainer {
    @Binds @IntoMap @TimelineItemEventContentKey(TimelineItemInteractiveUiContent::class)
    fun bindInteractiveUiPresenterFactory(factory: InteractiveUiPresenter.Factory): TimelineItemPresenterFactory<*, *>
}
```

Media protection: `TimelineProtectionState.hideMediaContent(eventId)` is already computed in
`TimelineItemEventContentView`, so the presenter never sees protection state. `InteractiveUiPresenter.present()` builds
the tree with `hideMedia = false`. `TimelineItemInteractiveUiView` (below) applies the protection with
`fun InteractiveUiViewState.withHiddenMedia(hide: Boolean): InteractiveUiViewState` (ui module, owned by T6), which sets
`hidden` on every `Image` and carousel `Item`. The view's "Show" control on a hidden image calls the timeline's existing
`onShowContentClick`, which reveals every protected item of the event, as for normal media.

View wiring (T6):

- `timeline/components/event/TimelineItemInteractiveUiView.kt`:
  `@Composable fun TimelineItemInteractiveUiView(content: TimelineItemInteractiveUiContent, hideMedia: Boolean,
  onShowContentClick: () -> Unit, onLinkClick: (Link) -> Unit, onLinkLongClick: (Link) -> Unit,
  onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit, modifier: Modifier = Modifier)`.
  `Valid` uses the presenter plus `InteractiveUiView(onOpenUrl = { onLinkClick(Link(url = it, text = it)) },
  fallback = { TimelineItemTextView(content.fallback, …) })`. `Invalid` shows `TimelineItemTextView(content.fallback)`
  plus the caption when `showCaption`.
- `TimelineItemEventContentView`: a branch `is TimelineItemInteractiveUiContent ->` placed **before**
  `is TimelineItemTextBasedContent`.
- `TimelineItemEventRow`: `TimestampPosition.Below` for this content, and the bubble gets
  `BubbleState.isFullWidth = true` for `Valid` content. `MessageEventBubble` then uses `fillMaxWidth()` with
  `widthIn(max = 680.dp)` instead of the 78 % ratio (R11).
- `protection/TimelineItem.kt` exhaustive `when`: add the new type (`false`, like text).
- Gestures: graph, table, chart and carousel use `horizontalScroll`/`HorizontalPager`, which claim horizontal drags
  inside the same `SwipeSensitivity` slop, so they win over swipe-to-reply and the drawer. Elsewhere on the card,
  swipe-to-reply works as today. A Robolectric test on the real `TimelineItemEventRow` proves both.

### 2.7 Error handling and logging

- The parser never throws: an internal exception carries `{path, code}`, and any other `Throwable` maps to
  `("", bad_type)`. The factory wraps `debugInfo()` and HTML parsing in `runCatchingExceptions` and falls back to the
  text content.
- `Timber.w("interactive_ui invalid: %s %s", path, code)` once per parse failure. Send failures log the exception class
  only. Never log payload strings, values, labels, URLs, bodies or nonces.
- Media failures show the alt text. Corrupt saved state is ignored (initial state). Store I/O errors are swallowed
  after a `Timber.w` with the exception class.
- Composables guard every index (carousel index clamped, unknown option values shown as "Choose an option").

### 2.8 Conventions

- Appyx + Molecule: presenter per item via the existing map multibinding, a stateless view, `eventSink` in the state,
  and Metro DI (`@Inject`, `@AssistedInject`/`@AssistedFactory`, `@ContributesBinding`, `@SingleIn`).
- Compound tokens and components only. Series are distinguished by marker shape and dash, never by colour alone, using
  the decorative palette (`ElementTheme.colors.bgDecorative1…6`/`textDecorative1…6`).
- Previews: `@PreviewsDayNight`, `internal`, wrapped in `ElementPreview`, fed by `InteractiveUiViewStatePreviewParam`
  (and `InteractiveUiGraphLayoutPreviewParam`, `InteractiveUiChartModelPreviewParam`, `InteractiveUiTableViewPreviewParam`).
  They cover active, sending, awaiting, overdue, submitted, send-failed, show-as-text, invalid-field, every node family
  and 360 dp. `:libraries:interactiveui:ui` is added to `allLibrariesImpl()`, so `tests/uitests` picks up the previews.
  Baselines are recorded by CI only.
- Vendored data: `libraries/interactiveui/core/src/main/resources/interactive-ui/interactive-ui-v1.schema.json`,
  `libraries/interactiveui/core/src/test/resources/interactive-ui/fixtures/{README.md,valid/*,invalid/*}` (an
  untouched copy), and `libraries/interactiveui/core/VENDORED.md` (source repo, path, commit `bcb08017…`, licence
  `AGPL-3.0-only OR LicenseRef-Element-Commercial`, original copyright `2026 sinkerine`, a sha256 manifest, and an update
  procedure: copy the whole set, never edit individual fixtures).
- Kotlin headers per R15. Format with `./gradlew ktlintFormat`; hard wrap at 160 characters.

### 2.9 Not in A1

Hermes (H1), any live GPT, computed formulas, maps, games, video/audio nodes, date pickers, image hotspots, streaming
payloads, cross-device sync of unsubmitted state, iOS, and any Rust SDK change (R1-SDK stays optional).

---

## 3. Tasks

Waves run in order. Tasks in the same wave have disjoint files and no mutual dependencies. Gradle module scaffolding is
owned by the first task that creates the module. **(H)** marks tasks that need high reasoning.

### Wave 1

**T1 — Protocol core: vendored contract, parser, state rules, action content (H)**

- Goal: make Android agree exactly with Web's parser, state rules and action content, proven by the shared fixtures.
- Owns: `libraries/interactiveui/core/**` except `session/InteractiveUiSession.kt` and its test. That is: the build file
  (`io.element.android-library`, kotlinx-serialization-json, okhttp, coroutines, timber), `VENDORED.md`, the vendored
  schema and fixtures, `protocol/*`, `state/*`, `action/*`, `session/InteractiveUiPhase.kt`, and tests
  `InteractiveUiConformanceTest`, `InteractiveUiParserLimitsTest`, `InteractiveUiParserFuzzTest`, `JsSemanticsTest`,
  `InteractiveUiUrlTest` (with `url-cases.json` generated by Node `new URL`), `InteractiveUiStateRulesTest`
  (port of Web `state.test.ts`), `InteractiveUiActionContentBuilderTest` (port of `action.test.ts` plus golden
  bodies), `VendoredManifestTest`.
- Produces: every `core` signature in §2.3 except `InteractiveUiSession`/`InteractiveUiSessionState`.
- Consumes: nothing new.
- Depends on: none (R18 setup is a shared prerequisite).
- Command: `./gradlew :libraries:interactiveui:core:testDebugUnitTest`

**T2 — Platform hooks: raw send and Labs flag**

- Goal: the two small platform capabilities that the integration needs.
- Owns: `libraries/matrix/api/.../room/JoinedRoom.kt` (one method), `libraries/matrix/impl/.../room/JoinedRustRoom.kt`
  (one override), `libraries/matrix/impl/src/test/.../fixtures/fakes/FakeFfiRoom.kt` (a `sendRaw` override),
  `libraries/matrix/impl/src/test/.../room/JoinedRustRoomSendRawTest.kt`, `libraries/matrix/test/.../room/FakeJoinedRoom.kt`,
  `libraries/featureflag/api/.../FeatureFlags.kt`.
- Produces: `JoinedRoom.sendRaw`, `FeatureFlags.InteractiveUi`.
- Consumes: the AAR's `Room.sendRaw`.
- Depends on: none.
- Command: `./gradlew :libraries:matrix:impl:testDebugUnitTest --tests '*JoinedRustRoomSendRawTest*' :libraries:featureflag:impl:testDebugUnitTest`

**T3 — Data-viz views: graph, chart, table (H)**

- Goal: self-contained, accessible, deterministic Compose views for the three data-heavy kinds, with their pure layout
  math.
- Owns: `libraries/interactiveui/ui/build.gradle.kts` (android-compose-library; designsystem, uiStrings, coroutines),
  `libraries/interactiveui/ui/src/main/res/values/temporary.xml` (the data-viz keys), `ui/graph/*`, `ui/chart/*`,
  `ui/table/*`, `ui/model/InteractiveUiBadgeTone.kt`, their previews and preview params, and tests
  `LayeredGraphLayoutTest` (determinism, four sweeps, crossings no worse than input order on fixtures),
  `GraphGeometryTest` (ported numbers, labelled outside lanes), `NiceTicksTest` (zero range, negatives, huge, subnormal),
  `InteractiveUiGraphViewTest` (buttons in reading order, select/clear, list mode lists every edge, detail shown,
  horizontal scroll), `InteractiveUiChartViewTest` (summary description, data table toggle, single-value pie, negative
  pie explanation, markers and dashes per series), `InteractiveUiTableViewTest` (numeric/text sort, stable ties, nulls
  first, sort state description, first 50 rows plus show-all).
- Produces: `layoutLayeredGraph`, `graphGeometry`, `InteractiveUiGraphLayout`, `InteractiveUiGraphInput`,
  `InteractiveUiGraphView`, `InteractiveUiChartModel`, `niceTicks`, `InteractiveUiChartView`,
  `InteractiveUiTableColumn`, `InteractiveUiCell`, `sortedRowIndices`, `InteractiveUiTableView`,
  `InteractiveUiBadgeTone`.
- Consumes: designsystem only. This task does **not** depend on `core`, and sort state uses a local
  `(columnKey, descending)` pair that T4 adapts.
- Depends on: none.
- Command: `./gradlew :libraries:interactiveui:ui:testDebugUnitTest`

### Wave 2

**T4 — Tree renderer and controls (H)**

- Goal: `InteractiveUiView` for every non-data-viz node kind, the status/overdue/show-as-text/URL-dialog shell, and the
  carousel. It is a stateless view over `InteractiveUiViewState`.
- Owns: in `libraries/interactiveui/ui`: adds `implementation(projects.libraries.interactiveui.core)`,
  `matrixmedia.api`, `matrix.api` and `htmlrenderer.api` to the build file; `model/InteractiveUiViewState.kt`,
  `model/InteractiveUiViewEvent.kt`, `model/InteractiveUiRenderNode.kt`, `InteractiveUiView.kt`, `components/*`
  (controls, form, carousel, link, image, status), `InteractiveUiViewStatePreviewParam.kt`, previews, the remaining
  `temporary.xml` keys (appended to T3's file), and tests `InteractiveUiViewTest` (each kind renders and emits the right
  event; disabled and pending states; unknown fallback; show-as-text swaps in the fallback; status and overdue retry;
  URL dialog Confirm/Cancel; horizontal stacks wrap at 360 dp; every control has a label and role), `InteractiveUiNumberFieldTest`
  (draft text `-`, `1.`, `12,5`; no reset; slider steps), `InteractiveUiTextFieldImeTest` (IME Done and composition
  never emit SubmitForm; maxLength), `InteractiveUiCarouselTest` (n of m, next/previous, settled swipe = +1,
  index kept across width change and recomposition, one-item card).
- Produces: `InteractiveUiView`, `InteractiveUiViewState`, `InteractiveUiViewEvent`, `InteractiveUiRenderNode`.
- Consumes: T1 (`InteractiveUiValue`, `InteractiveUiNode` enums, phase types), T3 views.
- Depends on: T1, T3.
- Command: `./gradlew :libraries:interactiveui:ui:testDebugUnitTest`

**T5 — Interactive session state machine (H)**

- Goal: the platform-neutral port of Web's view-model logic: local state, drafts, invalid keys, view-only state, D6
  phases, overdue timer, revisions, URL requests and debounced persistence.
- Owns: `libraries/interactiveui/core/src/main/.../session/InteractiveUiSession.kt` and
  `libraries/interactiveui/core/src/test/.../session/InteractiveUiSessionTest.kt`.
- Produces: `InteractiveUiSession`, `InteractiveUiSessionState`.
- Consumes: T1 (rules, builder, nonce, phase types).
- Depends on: T1.
- Tests (virtual time with `runTest`): each mutator and its no-op when inactive; conditions; number drafts block
  submit; required fields; sending → active for repeatable actions; sending → awaiting → overdue at exactly 8,000 ms →
  retry sends nothing; awaiting → submitted when a revision arrives; a revision during the send; a send failure; a lower
  revision ignored; revision merge keeps compatible values; persistence is debounced at 200 ms and flushed on dispose;
  the saved envelope is applied only while pristine; URL confirm only while active and on the same revision; the sent
  JSON equals the builder output, with a fresh nonce per send.
- Command: `./gradlew :libraries:interactiveui:core:testDebugUnitTest --tests '*InteractiveUiSessionTest*'`

### Wave 3

**T6 — Timeline integration (H)**

- Goal: make the feature real in the timeline: parse, render, send, persist, protect media, size the bubble, and keep
  gestures right.
- Owns:
  - `libraries/interactiveui/ui/src/main/.../InteractiveUiRenderTreeBuilder.kt` (+ `withHiddenMedia`) and
    `InteractiveUiRenderTreeBuilderTest.kt`.
  - `features/messages/impl/build.gradle.kts` (dependencies on both new modules).
  - `timeline/model/event/TimelineItemInteractiveUiContent.kt`.
  - `timeline/factories/event/TimelineItemContentInteractiveUiFactory.kt` and the hook in
    `TimelineItemContentFactory.kt`.
  - `interactiveui/{InteractiveUiStateStore,DataStoreInteractiveUiStateStore,InteractiveUiSessionRegistry,InteractiveUiPresenter}.kt`
    and `interactiveui/di/InteractiveUiPresenterBindingContainer.kt`.
  - `timeline/components/event/TimelineItemInteractiveUiView.kt` and the branch in `TimelineItemEventContentView.kt`.
  - `TimelineItemEventRow.kt` (timestamp, full-width flag), `MessageEventBubble.kt` and `BubbleState`
    (`isFullWidth`), `protection/TimelineItem.kt`.
  - `features/messages/impl/src/main/res/values/temporary.xml` (`screen_interactive_ui_invalid_content` if the caption
    lives here).
  - `plugins/src/main/kotlin/extension/DependencyHandleScope.kt` (`allLibrariesImpl` gets `:libraries:interactiveui:ui`;
    `core` arrives transitively).
  - Tests: `TimelineItemContentInteractiveUiFactoryTest` (flag off, local echo, no key, valid, invalid, bad_version,
    latest edit used, edit without payload, debugInfo throws, `m.notice` ignored, rich text unwrapped, media sources for
    `mxc` and `{"file": …}`), `DataStoreInteractiveUiStateStoreTest` (round trip, 64 KB cap, 200-entry eviction, clear
    on session deletion, corrupt entries ignored), `InteractiveUiSessionRegistryTest` (one session per event, revision
    applied, sendRaw called with `m.room.message` and builder JSON, thread relation), `InteractiveUiPresenterTest`
    (Molecule/Turbine: initial state, events reach the session, revision via new content keeps state),
    `TimelineItemInteractiveUiViewTest` (valid, invalid caption, version-2 no caption, show-as-text invokes the fallback
    slot; `TimelineItemTextView` renders nothing under `LocalUiTestMode`, so the test asserts the slot, not its text),
    `TimelineItemEventRowInteractiveUiGesturesTest` (real row: drag in carousel/graph pages or scrolls without reply;
    drag on the card header triggers reply; full-width bubble at 360 dp has no overflow), and updates to existing
    `TimelineItemContentFactoryTest`/`TimelineItemEventRow` tests if signatures change.
- Produces: the user-visible feature.
- Consumes: T1, T2, T4, T5.
- Depends on: T1, T2, T3, T4, T5.
- Command: `./gradlew :features:messages:impl:testDebugUnitTest --tests '*InteractiveUi*' --tests '*TimelineItemContentFactory*' --tests '*TimelineItemEventRow*' :libraries:interactiveui:ui:testDebugUnitTest --tests '*InteractiveUiRenderTreeBuilderTest*'`

### Wave 4

**T7 — Encrypted e2e harness, journey and verification record (H)**

- Goal: prove the feature on a dedicated emulator against a dedicated Synapse in an E2EE room (§5), and record the
  results honestly.
- Owns: `tools/interactive-ui/fixture-bot/{package.json,package-lock.json,.gitignore,README.md,bot.mjs,lib/*.mjs}`,
  `tools/interactive-ui/sandbox/{synapse-up.sh,synapse-down.sh,avd-up.sh}`,
  `.maestro/tests/interactiveUi/interactiveUiJourney.yaml` (standalone, **not** in `allTests.yaml`), and
  `docs/features/interactive-ui-verification.md`. Repairs found by e2e go back to the owning task's files under the
  user's standing authorisation, each with a regression test.
- Produces: the evidence under `build/e2e/interactive-ui/<UTC>/` and the verification document.
- Consumes: everything.
- Depends on: T6.
- Commands: `node --test tools/interactive-ui/fixture-bot` (bot self-tests: AES-CTR attachment round trip, fixture
  patching, fallback text) and the §5 runbook.

### Delivery boundaries (repository rule: ≤ 500 production additions per PR)

Estimated production additions: T1 ≈ 1,150, T2 ≈ 40, T3 ≈ 900, T4 ≈ 1,000 (previews included), T5 ≈ 350, T6 ≈ 550,
T7 ≈ 0 (tooling, tests, docs). That is about 4,000 lines, so the work is delivered as a **stacked series** of commits,
and each future PR is reviewable on its own. No push or PR happens without the user's decision.

| PR    | Contents                                                                                    | Tasks       | ≈ prod lines           |
| ----- | ------------------------------------------------------------------------------------------- | ----------- | ---------------------- |
| PR-0  | Vendor the v1 schema and fixtures (`VENDORED.md`, manifest test)                            | T1 (part)   | 0 code, 1,578 JSON (vendored data) |
| PR-1  | Core module, wire model, JSON safety, schema interpreter, parser                            | T1          | 500                    |
| PR-2  | Relational validation, state rules, action builder, nonce, phase types                      | T1          | 450                    |
| PR-3  | Interactive session state machine                                                           | T5          | 350                    |
| PR-4  | Graph layout, geometry and graph view                                                       | T3          | 450                    |
| PR-5  | Chart and table views                                                                       | T3          | 450                    |
| PR-6  | Tree renderer: layout, content and controls                                                 | T4          | 500                    |
| PR-7  | Carousel, image, link, status shell and previews                                            | T4          | 500                    |
| PR-8  | Raw send, Labs flag, content type, parsing factory                                          | T2, T6      | 300                    |
| PR-9  | Session registry, state store, presenter, view wiring, full-width bubble                    | T6          | 400                    |
| PR-10 | Fixture bot, sandbox scripts, Maestro flow, verification document                           | T7          | 0                      |

Each PR with previews gets the `Record-Screenshots` label, and each has exactly one `pr-` label (proposed
`pr-feature`). Inside this worktree there is one commit per task: T1 and T3 are each split at the PR-1/PR-2 and
PR-4/PR-5 boundaries, and the plan document has its own commit. Commit messages have a title and description.

---

## 4. Test and verification (whole feature)

Prerequisites: R18 (the AAR copied and its hash recorded) and `nix develop` (or direnv) for JDK 21 and SDK 37.

```sh
# Targeted unit and Compose tests
./gradlew :libraries:interactiveui:core:testDebugUnitTest :libraries:interactiveui:ui:testDebugUnitTest \
  :libraries:matrix:impl:testDebugUnitTest --tests '*JoinedRustRoomSendRawTest*' \
  :features:messages:impl:testDebugUnitTest
# Architecture, style, static analysis
./gradlew :tests:konsist:testDebugUnitTest
./gradlew detekt ktlintCheck
./gradlew :app:lintGplayDebug lintDebug --continue
# Types/compile for both flavours, then the e2e artifact
./gradlew :app:compileGplayDebugKotlin :app:compileFdroidDebugKotlin
./gradlew :app:assembleGplayDebug
# Docs TOC check (CI-equivalent)
python3 ./tools/docs/generate_toc.py --verify ./*.md docs/**/*.md
# Coverage of the new modules (report only)
./gradlew :libraries:interactiveui:core:koverHtmlReportDebug :libraries:interactiveui:ui:koverHtmlReportDebug
# Full regression, as in CI (long; record any unrelated failures explicitly, never claim universal green)
./gradlew :app:testGplayDebugUnitTest testDebugUnitTest -x :tests:konsist:testDebugUnitTest
# Bot self-tests
(cd tools/interactive-ui/fixture-bot && npm ci && node --test)
```

Screenshots: do **not** run `recordPaparazziDebug`. `verifyPaparazziDebug` would fail on the new previews until CI
records baselines, so it is reported as "expected missing baselines" rather than run as a gate. Exact Kover task names
are confirmed at T1 time (`./gradlew :libraries:interactiveui:core:tasks --group verification`). If they differ, the
plan's command is updated rather than skipped.

---

## 5. E2E journey

Follows `AGENTS.md` and `.agents/skills/element-x-android-e2e/SKILL.md`. Everything runs from this worktree.

### 5.1 Fixture and sandbox setup (all dedicated, R19)

1. **Preflight.** Run `git branch --show-current` (must be `interactive-ui-android`) and `git status`; record
   `git rev-parse HEAD`. R18: copy the AAR and record its sha256.
2. **Build.** Run `./gradlew :app:assembleGplayDebug` and record the result. The artifact is
   `app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk`.
3. **Homeserver.** `tools/interactive-ui/sandbox/synapse-up.sh` does the following:
   - Checks that port 18028 is free (`ss -ltn`).
   - Generates `build/e2e/interactive-ui/synapse/homeserver.yaml` (server name `interactive-ui.test`, SQLite, a random
     `registration_shared_secret`, public registration off, relaxed rate limits, 10 MB uploads, URL previews off) with
     `docker run --rm … generate`.
   - Starts `element-x-interactive-ui-synapse` (`-p 127.0.0.1:18028:8008`, the image pinned by the RepoDigest of the
     local `matrixdotorg/synapse:latest`, recorded).
   - Waits for `/_matrix/client/versions`, with a 60 s timeout.
4. **Accounts.** `node tools/interactive-ui/fixture-bot/bot.mjs provision` registers `@alice:interactive-ui.test`
   (the Android user) and `@uibot:interactive-ui.test` (display name "Fixture sender") through the shared-secret admin
   API. Passwords are random and go to `~/.local/share/element-x-e2e/interactive-ui/credentials.json` (mode 0600). They
   are never printed.
5. **Bot.** `bot.mjs serve` keeps one in-memory Rust-crypto device for the whole run and bootstraps cross-signing.
   It exposes a control API on `127.0.0.1:18029` with a random bearer token in an owner-only file. It then runs
   `ctl setup-room`, which creates an **encrypted** private room "Interactive UI e2e" and invites Alice.
6. **Emulator.** `tools/interactive-ui/sandbox/avd-up.sh` creates the `interactive_ui_a1` AVD (pixel_7_pro, API 33
   google_apis x86_64, from `~/.local/share/element-x-e2e/android-sdk`, `ANDROID_AVD_HOME=~/.local/share/element-x-e2e/avds`)
   if it is missing. It boots the AVD headless on port 5584 and waits (bounded) for `sys.boot_completed == 1`. Then
   record the serial, API and ABI (`getprop ro.product.cpu.abi` must be `x86_64`).
7. **Install.** Once `adb devices` shows `emulator-5584` steadily, run
   `adb -s emulator-5584 install -r --no-incremental app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk`,
   once (never in a loop). Check that `dumpsys package io.element.android.x.debug` shows `firstInstallTime` older than
   `lastUpdateTime` on reinstalls. Then run `adb -s emulator-5584 reverse tcp:18028 tcp:18028`.
8. **Login.** Through Orca (or `adb shell input` where Orca is unavailable), log in to `http://127.0.0.1:18028` as
   Alice. Finish FTUE; skip recovery setup with "Skip"/"Remind me later" if offered. Accept the invite. Turn on
   Settings → Labs → "Interactive messages", then reopen the room. Assert in the accessibility tree that the switch is
   checked.

### 5.2 Journey (actual timeline order; each step lists its assertions)

| #  | Action                                                                                                                                                                                                     | Expected, observable assertions                                                                                                                                                                                                                                                                                                          |
| -- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | `ctl send knowledge-map.en` (encrypted). Wait (bounded, 30 s) for the card.                                                                                                                                 | The accessibility tree has the heading "Cooking knowledge map", a "Knowledge branch" dropdown, layer headings Ingredients/Mechanisms/Results, and node buttons Sugar/Maillard reaction/Flavour. The raw fallback body is not shown. The card is full width. Screenshot `01-knowledge-light.png`.                                     |
| 2  | `ctl mark`. Switch the branch to "Nutrition", back to "Cooking", tap "Maillard reaction", toggle "Relationships as list" on and off.                                                                       | The graph switches, the detail "Heat creates flavour" appears, the list shows "Sugar → Maillard reaction". `ctl count-events --since mark --sender alice` == 0.                                                                                                                                                                          |
| 3  | Rotate to landscape and back. `am force-stop`, relaunch, reopen the room.                                                                                                                                   | Branch "Cooking" and node "Maillard reaction" are still selected after each.                                                                                                                                                                                                                                                              |
| 4  | Tap "Explain this mechanism".                                                                                                                                                                              | The status is "Sending…", then it is interactive again with the button enabled. `ctl actions --since mark` has exactly 1: encrypted == true, `action_id` `explain_node`, `values` == `{focus: "maillard"}`, `revision` 0, `ui_event_id` == the step-1 id, nonce matches `^[A-Za-z0-9_-]{32}$`, `m.mentions.user_ids` == [uibot], `m.in_reply_to` == the UI event. The timeline shows Alice's reply “Explain this mechanism” — focus: Maillard reaction. |
| 5  | `ctl send controls --set /nodes/<submit>/action/single_use=true`. Tap "Send" (the submit button).                                                                                                          | "Waiting for confirmation…", with every control disabled. One action is received. Then run `ctl revise <id> --status submitted --status-text "Your choice was accepted"`: the card shows "Your choice was accepted", all controls stay disabled, and the card's top (from the accessibility tree bounds) moves by no more than 8 px.       |
| 6  | Send a second copy of step 5's payload (new `ui_id`), tap Send, and let the bot stay silent.                                                                                                               | 8–10 s later: "No confirmation from Fixture sender yet" and "Try again". Tap "Try again": the controls are enabled, and `ctl count-events` grows only by step 6's single action.                                                                                                                                                   |
| 7  | `ctl send-root "Thread question"`, then `ctl send form --thread <root>`. Open the thread. Tap Submit with the required field empty.                                                                         | Inline error "Check this value." and zero events.                                                                                                                                                                                                                                                                                    |
| 8  | In the same form, type "Hello wor" with the emulator's Gboard (so it composes with suggestions) and press the IME "Done". Then finish the word. In the number field type `-`, `1.`, then `12,5`. Tap Submit. | The composing text stays and nothing is sent on Done. The number field shows exactly what was typed at each stage. One action: thread relation `m.thread` → root with `is_falling_back: false`, `m.in_reply_to` → form event, values only for the form's descendant keys (`12.5` as a number), then "Waiting for confirmation…" (single-use default). |
| 9  | `ctl send table`, `chart-bar`, `chart-line`, `chart-pie`, `chart-scatter`, `parcel-compare`. Sort the table by price twice; open "Data table" on each chart.                                               | The sort state reads "sorted ascending"/"descending" and the rows reorder. Each chart has its summary description, and the data table shows every value. Screenshots in light mode.                                                                                                                                                  |
| 10 | Horizontally drag inside the graph and the table, then on the card title area.                                                                                                                             | Inside: the region scrolls; no reply composer, no drawer. On the title: reply mode starts (existing behaviour). Cancel the reply.                                                                                                                                                                                                    |
| 11 | `ctl upload-encrypted-png` ×5 (distinct solid-colour PNGs), then `ctl send product-carousel --encrypted-images`, and send `content` with one valid encrypted image and one with a corrupted `hashes.sha256`. | Carousel images decrypt (the screenshot shows the five colours; the accessibility tree has alt "Product 1"…). The corrupt image shows its alt text only. "Item 1 of 5".                                                                                                                                                                 |
| 12 | Tap Next twice, swipe one item, rotate to landscape and back, set `wm size 1080x2400` and `wm density 480` (360 dp), then swipe once more.                                                                 | "Item 3 of 5" after Next ×2, "Item 4 of 5" after the swipe (settled and aligned), still "Item 4 of 5" after rotation and resize, "Item 5 of 5" after the last swipe. No horizontal page overflow: every timeline row's bounds are within the screen width. Screenshot `12-carousel-360.png`.                                            |
| 13 | Tap the item action "Product 1" (an `open_url`), then Cancel. Tap a parcel-compare link chip, then Continue.                                                                                              | The dialog shows the full `https://example.org/item/1`. After Cancel the resumed activity is unchanged (`dumpsys activity activities | grep mResumedActivity`). After Continue a browser or Custom Tab resumes; press Back to return.                                                                                                     |
| 14 | `ctl send-invalid bad-type`, `ctl send-version 2`.                                                                                                                                                          | Both render as normal text messages. Only the invalid one shows "Couldn't show interactive content."                                                                                                                                                                                                                                    |
| 15 | `cmd uimode night yes`; screenshot knowledge map, form, charts, carousel. `settings put system font_scale 2.0`; screenshot the form. Set `wm size 1600x2560` / `wm density 320` (800 dp); screenshot `venue-compare`. | Dark colours apply. At 200 % font size, labels wrap and nothing overlaps or clips (checked visually and with tree bounds). At 800 dp the card is capped at 680 dp and the horizontal stack of venue cards sits side by side.                                                                                                       |
| 16 | Accessibility pass: dump the tree (`uiautomator dump` or `ORCA emulator ax`) for steps 1, 8, 9 and 12. If `com.google.android.marvin.talkback` is installed, enable it and swipe through the knowledge-map card. | Every clickable node has text or a content description. Graph nodes are clickable with their label. The chart has the summary. Table headers have their sort state. The carousel position is a live region. Switches and checkboxes expose a checked state. If TalkBack is not installed, record that as a limitation, not a pass.      |
| 17 | Labs → turn "Interactive messages" off, then reopen the room.                                                                                                                                              | Every UI event renders as its body text. Alice's action replies show the normal reply preview.                                                                                                                                                                                                                                        |
| 18 | Optional repeatable flow: `MAESTRO_APP_ID=io.element.android.x.debug maestro --device emulator-5584 test --test-output-dir build/e2e/interactive-ui/<UTC>/maestro .maestro/tests/interactiveUi/interactiveUiJourney.yaml`, with the bot token passed through the environment. | Steps 1, 2 and 4 pass via Maestro `runScript` `http.post` calls to the bot.                                                                                                                                                                                                                                                         |

### 5.3 Evidence

All evidence goes under ignored `build/e2e/interactive-ui/<UTC>/`:

- `summary.md`: per step pass/fail/blocked, the commit, AAR hash, APK variant, emulator serial/API/ABI, and Synapse
  image digest.
- `screenshots/*.png`: each one is reviewed for overflow, clipping, overlap, obscuring overlays or spinners before it
  counts.
- `ax/*.json|xml`: accessibility dumps, with message bodies redacted before sharing.
- `bot/actions.jsonl`: received action content. Nonces are kept only as length and charset checks.
- `logcat.txt`: bounded and filtered to the app PID. Grep confirms no payload strings appear.

`docs/features/interactive-ui-verification.md` summarises what was actually executed, the rulings and the limitations.
It states that Hermes/GPT were not involved.

### 5.4 Cleanup

Reset `wm size`/`wm density`, rotation, `uimode`, `font_scale` and TalkBack. Remove `adb reverse`. Stop the bot
(`ctl shutdown`) and leave/forget the room. Stop the Synapse container, keeping its data directory for reruns unless
removal is requested. Release the Orca helper. Shut down `interactive_ui_a1`, since this run started it, and keep the
AVD. Never touch the other worktrees' containers, AVDs or accounts. The Paseo workspace stays visible.

### 5.5 Known blockers to report precisely, never as passes

- A missing or mismatched AAR that needs a rebuild.
- Docker daemon or image pull failure.
- Missing `/dev/kvm` or system image.
- npm registry unreachable (`npm ci` fails).
- Orca or Maestro unavailable.
- Element X refusing the http homeserver.
- Login blocked by a recovery or verification prompt that cannot be skipped.
- TalkBack not installed on the `google_apis` image.

---

## 6. Open items for the user

None. The user approved all recommendations on 2026-11-03 (R15 headers, R17 in-repo fixture bot, R10, R11, R12, R16 as proposed).

## 7. Review log

- 2026-11-03: draft written by the A1 design agent from Web `develop@bcb08017` and Android `develop@816048e6`.
  Not yet approved.
- 2026-11-03: the user approved the plan and all recommended rulings.
