# Hermes slash commands (MSC4391 bot commands) for Element X Android: plan

Status: **APPROVED by the user (2026-11-08).** Branch `feat/hermes-slash`, base `develop` @ `0aa71ce4877a`.

Pipeline: a bot (Hermes is the main producer) publishes MSC4391 command descriptions as room state. Element X
suggests them after `/` and sends recognised commands as plain messages addressed to the bot.

Compatibility reference: Element Web @ `94f74b1b7a92e4f73e2bf87b7b49742c769d8c87`. The committed sources
`apps/web/src/slash-commands/bot-commands/{BotCommandProtocol,RoomBotCommands,BotCommandContent}.ts` and
`apps/web/src/autocomplete/CommandProvider.tsx` take precedence over the Web plan. Behaviour that Android adapts is
called out explicitly.

## Decisions

| #   | Question | Decision |
| --- | --- | --- |
| D1  | Discovery source | MSC4391 state events of type `org.matrix.msc4391.command_description`, one per command, with the bot as sender. The client is generic: there's no static Hermes list and no Labs flag. **Always on.** |
| D2  | How Android gets the state | **SDK fork change (user choice).** Under sliding sync the SDK stores room state only from `required_state`, and ignores state that arrives in the timeline (`matrix-sdk-base/src/response_processors/room/msc4186/mod.rs`). Add `(org.matrix.msc4391.command_description, "*")` to the room-subscription extra `required_state`. Android wraps the existing FFI `Room.subscribeToStateEvents`. An opened room is always subscribed (`JoinedRoomLoadedFlowNode` calls `subscribeToSync()`). |
| D3  | Built-in precedence | **Enabled built-ins only (user choice).** With `FeatureFlags.SlashCommand` on (default off), every name or alias the native `CommandParser` recognises wins a bare `/name`. `/crash` counts only in developer mode, which mirrors the parser. With the flag off, no built-in wins. `/name@bot` always reaches the bot. |
| D4  | Unknown or ambiguous | Native behaviour is kept. Flag off: sent as an ordinary message, as today. Flag on: the existing "Unrecognized command: /x" error, with the composer text kept. Android has no confirmation dialog. |
| D5  | Send path | Plain text through the **send queue** (local echo, retry), using the fork's `Timeline.sendWithExtraContent`. The body is `/<command>[ <args>]` with no `format`/`formatted_body`. `m.mentions` is `{user_ids:[bot]}`. The `org.matrix.msc4391.command` block is added only when the arguments map cleanly. |
| D6  | Replies | **Add an SDK FFI method (user choice):** `Timeline.sendReplyWithExtraContent(msg, eventId, extraContentJson, addMentions)`. Bot commands are recognised in `Normal` and `Reply` modes, in the main timeline and in threads. A reply never adds the replied-to sender to `m.mentions`. |
| D7  | Edits | Unchanged. `Edit` and `EditCaption` modes never parse commands today, so an edit into `/cmd` is sent as an ordinary edit with no command block. `Attachment` mode is also unchanged. |
| D8  | SDK landing | **Separate Paseo project (user choice, at approval).** The SDK change (§2.2) is built by its own e2e-feature run: project `matrix-rust-sdk` (`prj_c53e858ba0b57cae`), workspace `wks_42ff502587345df7`, worktree `/home/sinkerine/.paseo/worktrees/37dm06q5/feat-msc4391-bot-commands`, branch `feat/msc4391-bot-commands` off `refs/heads/main` @ `e8ebe8abf`, orchestrator Pi-sol tab `d0a55c38-a236-4e4f-bbdd-2ffb324fec0d`. It lands with `merge --ff-only` into `~/dev/matrix-rust-sdk` `main` only if that leaves the user's uncommitted WIP untouched; otherwise it asks the user. **No push.** When done it sends the Android orchestrator `SDK_READY <sha> …`. Android builds the AAR from a clean clone checked out at that commit (T1). |
| D9  | Hermes | The real Hermes publisher is the separate, nonblocking **H2** follow-up (Web plan §2.7 contract). The Hermes repo isn't modified, and nothing here claims Hermes publishes descriptions today. E2E uses a protocol stand-in bot. |

## 1. Goal and acceptance criteria

### Goal

In a room where any joined MSC4391 bot has published command descriptions:

- typing `/` suggests the bot's commands, including skill and plugin commands, after the enabled native built-ins;
- sending a recognised bot command posts a plain `/cmd args` message addressed only to that bot, with no error.

### What exists today (evidence)

- **Built-in commands.** `libraries/slashcommands` (`SlashCommandService`, `CommandParser`, `Command`) handles them,
  gated by `FeatureFlags.SlashCommand`, which defaults to `false`.
    - `getSuggestions` does prefix matching only and returns `[]` when the flag is off.
    - `parse` returns `NotACommand` when the flag is off, so `/x` is sent as Markdown.
- **When commands are parsed.** `MessageComposerPresenter.sendMessage` parses only in `Normal` mode. Reply and edit
  modes send the text as is.
- **Suggestion queries.** `MarkdownTextInput.checkSuggestionNeeded` uses the whole non-whitespace word after `/` as the
  query, so `-` and `@` already reach `SuggestionsProcessor`. Command rows only appear for a word starting at index 0.
  Suggestions disappear once the caret leaves the first word, so Android never shows rows while arguments are typed.
- **Room state.** There's no API-layer access to custom room state. `JoinedRoom.sendRaw` bypasses the send queue.
  `Timeline.sendMessage` can't carry extra content.
- **SDK build.** `libraries/matrix/impl` requires a locally built `libraries/rustsdk/matrix-rust-sdk.aar` from the
  fork's `main` branch (`tools/sdk/build-threads-inbox-sdk`). That file is **absent from this worktree** (see §4.0).

### Acceptance criteria

1. **Discovery.** Every valid description event in the room's current state is a candidate, under these conditions:
    - the sender is currently joined (`RoomMembershipState.JOIN`) and isn't the session user;
    - malformed or blanked (`{}`) content is ignored and never breaks the composer;
    - candidates are deduplicated by (sender, lower-cased command), and the newest `origin_server_ts` wins;
    - at most 500 commands per bot and 1000 per room are used, after sorting by bot user ID then command (UTF-16
      order, as in Web).
2. **Validation** matches `BotCommandProtocol.ts`:
    - `command` is 1–3 single-space-separated tokens, each matching `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`;
    - `aliases` has at most 10 valid tokens;
    - there are at most 32 `parameters`, each with a unique key matching `^[A-Za-z_][A-Za-z0-9_]{0,63}$`;
    - schemas follow the MSC nesting rules (union not inside a union variant; array only at top level);
    - `optional` is a boolean when present;
    - descriptions come from `m.text[0].body`, cut to 200 UTF-16 units.
3. **Suggestions.** Typing `/` lists the enabled built-ins first (unchanged, flag-gated), then every bot row in sorted
   order. Each bot row has:
    - a title of `/name`, or `/name@qualifier` when the bare name clashes with an enabled built-in or with another bot;
    - parameter usage, `<key>` for required and `[key]` for optional;
    - a description of `"<description> (from <bot name>)"`, or `"Command from <bot name>"`.

    Bot rows are filtered like Web's `QueryMatcher`, so `/mod` matches `/model`. Hyphenated names (`/reload-m`) and
    qualified queries (`/status@hel`) work. Selecting a row inserts `"/name "` or `"/name@qualifier "`.
4. **Qualifier.** The qualifier is the bot's localpart. When another bot in the room has the same localpart
   (case-insensitive), it's the full user ID without `@`. Resolution accepts either form, case-insensitively.
5. **Built-in precedence (D3).**
    - With the flag on, a bare `/topic x` runs the native topic change and sends no bot message, while
      `/topic@hermes x` sends body `/topic x` to the bot.
    - With the flag off, a bare `/topic x` reaches a bot that publishes `topic`.
6. **Recognition.** Sending `/model gpt-5.5 --provider openrouter` in a room where the bot publishes `model` (one
   optional string parameter `name`) shows no error, and sends exactly one `m.room.message` whose content is:
    - `msgtype: "m.text"` and `body: "/model gpt-5.5 --provider openrouter"`, with no `format` or `formatted_body`;
    - `"m.mentions": {"user_ids": ["@hermes:…"]}`;
    - `"org.matrix.msc4391.command": {"command": "model", "arguments": {"name": "gpt-5.5 --provider openrouter"}}`.

    User pills in the arguments become plain user IDs, in both Markdown and rich-text modes. Room-alias pills become the
    alias, and `@room` stays `@room`.
7. **Replies and threads.**
    - Reply mode sends `m.relates_to.m.in_reply_to` to the replied-to event, and `m.mentions` holds only the bot even
      when replying to another user.
    - In a thread, the event carries `rel_type: "m.thread"` with the thread root. A thread reply also targets the
      replied event, with `is_falling_back: false`.
8. **Edits.** Editing a message into `/arxiv vision` sends an `m.replace` edit whose `m.new_content` has no
   `org.matrix.msc4391.command`. It never sends a new command message.
9. **Ambiguity and unknown (D4).**
    - A bare name offered by two bots isn't routed. It is sent as an ordinary message with the flag off, or shows the
      unrecognized error with the flag on.
    - Both qualified rows are suggested.
    - A name nobody publishes behaves exactly as on `develop`.
10. **Skills.** A published skill command (`arxiv`, one optional string `args`) is suggested and sent the same way.
11. **Freshness.** Each keystroke or send uses the latest synced state:
    - a republished description shows its new text;
    - a description blanked to `{}` disappears;
    - a bot that leaves takes its commands with it, and sending its command afterwards behaves like an unknown command.
12. **No bot commands.** In a room with no valid descriptions, behaviour is exactly as on `develop`.
13. **Privacy.** Logs never contain message bodies, arguments or description content. Matrix IDs and counts are
    allowed.
14. **Quality.** Every new or changed module has unit tests. The §4 checks pass, and the §5 journey passes on a
    dedicated emulator.

## 2. Design

### 2.1 Modules and data flow

```
bot ──state org.matrix.msc4391.command_description──▶ homeserver ──sliding sync room subscription
                                                     required_state (+MSC4391, "*") [SDK project, D8]
                                                                    │
libraries/matrix/api+impl   JoinedRoom.stateEventsFlow(type) ◀── FFI Room.subscribeToStateEvents
                            Timeline.sendPlainTextWithExtraContent ──▶ FFI sendWithExtraContent /
                                                                       sendReplyWithExtraContent [SDK project, D8]
                                                                    │
libraries/slashcommands/impl
   botcommands/BotCommandProtocol.kt   JSON → BotCommandDescription (pure)
   botcommands/RoomBotCommands.kt      descriptions + joined bots → RoomBotCommand; text → resolved; matcher
   botcommands/BotCommandContent.kt    args mapping → BotCommandMessage (body + extra JSON)
   botcommands/RoomBotCommandsSource   @SingleIn(RoomScope): live state snapshot + member lookup
   DefaultSlashCommandService          suggestions (built-ins then bots), resolveBotCommand, sendBotCommand
                                                                    │
features/messages/impl   SuggestionsProcessor (unchanged) / SuggestionsPickerView (row key)
                         MessageComposerPresenter.sendMessage: Normal|Reply → resolveBotCommand(plainText)
                            → sendBotCommand(current timeline, repliedTo) ; else existing flow unchanged
libraries/textcomposer   MarkdownTextEditorState.getMessagePlainText()
```

There's no Labs flag. With no valid descriptions, every new branch returns empty or `null`, and the existing code
paths run unchanged.

### 2.2 SDK fork (built by the separate SDK Paseo project, D8; consumed by T1)

`crates/matrix-sdk-ui/src/room_list_service/mod.rs`:

```rust
/// The default `required_state` constant value for sliding sync room
/// subscriptions that must be added to `DEFAULT_REQUIRED_STATE`.
/// MSC4391 bot command descriptions are only needed for opened rooms.
const DEFAULT_ROOM_SUBSCRIPTION_EXTRA_REQUIRED_STATE: &[(StateEventType, &str)] = &[
    (StateEventType::RoomPinnedEvents, ""),
    (StateEventType::_Custom(..) /* "org.matrix.msc4391.command_description" */, "*"),
];
```

`StateEventType::from("org.matrix.msc4391.command_description")` isn't `const`, so the implementer turns the constant
into a function or `LazyLock` returning the same pairs. No list (room-list) `required_state` changes, so sync payloads
for unopened rooms stay the same.

`crates/matrix-sdk-ui/src/timeline/mod.rs`:

```rust
/// Like [`Self::send_reply`], merging `extra_content` top-level fields into the content.
/// `add_mentions` controls whether the replied-to sender is added to `m.mentions`.
pub async fn send_reply_with_extra_content(
    &self,
    content: RoomMessageEventContentWithoutRelation,
    in_reply_to: OwnedEventId,
    add_mentions: AddMentions,
    extra_content: Option<serde_json::Map<String, serde_json::Value>>,
) -> Result<SendHandle, Error>;
```

Its body: `infer_reply(Some(id))`, then override `add_mentions`, then `make_reply_event`, then the shared queue send
with `with_extra_content`. `send_reply` delegates to it with `AddMentions::Yes, None`, so behaviour doesn't change.

`bindings/matrix-sdk-ffi/src/timeline/mod.rs`:

```rust
pub async fn send_reply_with_extra_content(
    &self,
    msg: Arc<RoomMessageEventContentWithoutRelation>,
    event_id: String,
    extra_content_json: Option<String>,
    add_mentions: bool,
) -> Result<Arc<SendHandle>, ClientError>;
```

Invalid JSON or an invalid event ID returns `ClientError`, like the existing methods. Each changed crate's
`CHANGELOG.md` gets entries.

Tests:
- `crates/matrix-sdk-ui/tests/integration/room_list_service.rs`: update the room-subscription `required_state`
  expectation.
- `crates/matrix-sdk-ui/tests/integration/timeline/replies.rs`: cover two cases. First, a reply with extra content in
  the live timeline gives `m.in_reply_to`, the extra key present, and `m.mentions` unchanged with no replied-to sender
  when `AddMentions::No`. Second, the same in a thread-focused timeline gives `rel_type m.thread` and
  `is_falling_back: false`.

### 2.3 Matrix wrapper (T2: `libraries/matrix/{api,impl,test}`)

`api/.../room/RoomStateEvent.kt` (new):

```kotlin
/** A room state event as stored by the SDK; [contentJson] is the raw `content` object. */
data class RoomStateEvent(
    val eventType: StateEventType,
    val stateKey: String,
    val sender: UserId,
    val contentJson: String,
    val eventId: EventId?,
    val timestamp: Long?,
)
```

`api/.../room/JoinedRoom.kt` (add):

```kotlin
/**
 * Emits the current state events of [eventType], one per state key, immediately and after every sync that changes
 * any of them. Custom types are only populated when the SDK requests them in `required_state`.
 */
fun stateEventsFlow(eventType: StateEventType): Flow<List<RoomStateEvent>>
```

`api/.../timeline/Timeline.kt` (add):

```kotlin
/**
 * Sends [body] as an unformatted `m.text` message through the send queue, merging the top-level fields of
 * [extraContentJson] (a JSON object, or null) into its content. With [repliedToEventId] it is sent as a reply that
 * does not add the replied-to sender to `m.mentions`. Thread timelines add their thread relation.
 */
suspend fun sendPlainTextWithExtraContent(
    body: String,
    intentionalMentions: List<IntentionalMention>,
    extraContentJson: String?,
    repliedToEventId: EventId?,
): Result<Unit>
```

Implementation:
- `JoinedRustRoom.stateEventsFlow` uses `mxCallbackFlow { innerRoom.subscribeToStateEvents(eventType.map(), listener) }`.
  It maps each FFI `RoomStateEvent`, dropping (with `Timber.w`, no content) any entry whose sender fails `UserId`
  validation.
- `RustTimeline.sendPlainTextWithExtraContent` builds
  `MessageEventContent.from(body, null, mentions, asPlainText = true)`. It then calls
  `inner.sendWithExtraContent(content, extra)` or `inner.sendReplyWithExtraContent(content, id, extra, false)` inside
  `runCatchingExceptions` on the timeline dispatcher. The content is closed with `use`.

Fakes:
- `FakeJoinedRoom`: `stateEventsFlowResult: (StateEventType) -> Flow<List<RoomStateEvent>>`.
- `FakeTimeline`: `sendPlainTextWithExtraContentLambda`.
- `FakeFfiRoom` and `FakeFfiTimeline`: overrides capturing their arguments.

### 2.4 Bot command logic (T3 pure, T4 wiring: `libraries/slashcommands/{api,impl,test}`)

`impl/.../botcommands/BotCommandProtocol.kt` (T3, a port of `BotCommandProtocol.ts`), parsed with
`kotlinx.serialization.json.JsonElement`:

```kotlin
internal const val BOT_COMMAND_DESCRIPTION_EVENT_TYPE = "org.matrix.msc4391.command_description"
internal const val BOT_COMMAND_CONTENT_KEY = "org.matrix.msc4391.command"

internal enum class BotCommandPrimitiveType(val wireName: String) {
    STRING("string"), INTEGER("integer"), BOOLEAN("boolean"), USER_ID("user_id"),
    SERVER_NAME("server_name"), ROOM_ALIAS("room_alias"), ROOM_ID("room_id"), EVENT_ID("event_id"),
}
internal sealed interface BotCommandLiteralValue {
    data class Text(val value: String) : BotCommandLiteralValue
    data class Integer(val value: Long) : BotCommandLiteralValue
    data class Bool(val value: Boolean) : BotCommandLiteralValue
}
internal sealed interface BotCommandSchema {
    data class Primitive(val type: BotCommandPrimitiveType) : BotCommandSchema
    data class Literal(val literalType: BotCommandPrimitiveType, val value: BotCommandLiteralValue) : BotCommandSchema
    data class Union(val variants: List<BotCommandSchema>) : BotCommandSchema
    data class ArrayOf(val items: BotCommandSchema) : BotCommandSchema
}
internal data class BotCommandParameter(val key: String, val schema: BotCommandSchema, val description: String, val optional: Boolean)
internal data class BotCommandDescription(
    val botUserId: UserId,
    val command: String,
    val aliases: List<String>,
    val description: String,
    val parameters: List<BotCommandParameter>,
    val ts: Long,
)

/** Validates one description; returns null (never throws) for anything unusable. */
internal fun parseBotCommandDescription(sender: UserId, contentJson: String, timestamp: Long?): BotCommandDescription?
```

Integer literals accept a JSON number that is a safe integer (|n| ≤ 2^53−1, with integral doubles like `1.0` accepted
as JS does). `ts` is `timestamp ?: 0`.

`impl/.../botcommands/RoomBotCommands.kt` (T3, a port of `RoomBotCommands.ts` plus `CommandProvider` filtering):

```kotlin
internal data class RoomBotCommand(
    val description: BotCommandDescription,
    /** Disambiguated room display name, falling back to the user ID. */
    val botName: String,
    /** Localpart, or the full user ID without "@" when another bot shares the localpart. */
    val qualifier: String,
    /** An enabled built-in or another bot offers one of this command's names. */
    val requiresQualifier: Boolean,
) {
    /** "/<command>" or "/<command>@<qualifier>". */
    val insertedCommand: String
}
internal data class ResolvedBotCommand(val command: RoomBotCommand, val argsText: String)

internal fun buildRoomBotCommands(
    descriptions: List<BotCommandDescription>,
    joinedBotNames: Map<UserId, String>, // joined, non-self senders only → display name
    isBuiltIn: (String) -> Boolean,
): List<RoomBotCommand>
internal fun resolveBotCommand(commands: List<RoomBotCommand>, text: String, isBuiltIn: (String) -> Boolean): ResolvedBotCommand?
internal fun matchBotCommands(commands: List<RoomBotCommand>, query: String): List<RoomBotCommand>
internal fun formatBotCommandUsage(parameters: List<BotCommandParameter>): String
```

Rules:
- **Dedupe.** The key is `"$botUserId\u0000${command.lowercase()}"`, and a strictly newer `ts` replaces. To be
  deterministic regardless of store order, the input is first sorted by (state key, event ID). This refines Web, whose
  tie order depends on store iteration.
- **Sort and cap.** Sort by `botUserId.value`, then by `command` as published, using `String.compareTo`. Keep at most
  500 per bot, then the first 1000.
- **Names.** Names are `command` plus aliases, lower-cased.
- **Built-in check.** `isBuiltIn(name)` is supplied by the service (§2.4 wiring).
- **Resolution.** This ports `resolveBotCommand` exactly:
    - text that doesn't start with `/`, or starts with `//`, is rejected;
    - names are matched by prefix, case-insensitively;
    - an optional `@qualifier` is the following non-whitespace run, and must equal `qualifier` or the full ID without
      `@`, case-insensitively;
    - the character after the match must be the end of input or whitespace;
    - the longest name wins;
    - a bare match returns null when `isBuiltIn(firstToken)` holds, or when another command entry matched the same name;
    - `argsText` is the rest with leading whitespace trimmed.
- **Whitespace.** "Whitespace" means Kotlin `Char.isWhitespace()`, which includes U+00A0 like JS `\s`. Don't use Java
  regex `\s`, which is ASCII-only.
- **Matcher.** This is a `QueryMatcher` port:
    - lower-case the query and every key, then strip everything except `[A-Za-z0-9_]`;
    - an empty query gives `[]`;
    - the keys, in weight order, are `command`, `description`, `botName`, then the aliases joined with a space;
    - a match is a substring match;
    - sort by match index, then key weight, keeping the order stable;
    - dedupe, keeping the first occurrence.
- **Suggestion query.** For a query `name@qual` (split at the first `@`), match on `name`. Then keep the commands whose
  qualifier, or full ID without `@`, starts with `qual` (case-insensitive).
- **Bare `/`.** An empty query lists every command.

`impl/.../botcommands/BotCommandContent.kt` (T3, a port of `BotCommandContent.ts`):

```kotlin
/** Maps free text to MSC4391 arguments in required-then-optional order; null when not cleanly mappable. */
internal fun mapBotCommandArguments(parameters: List<BotCommandParameter>, argsText: String): JsonObject?
/** Builds the plain body and the extra-content JSON for [resolved]. */
internal fun createBotCommandMessage(resolved: ResolvedBotCommand): BotCommandMessage
```

Mapping rules match Web exactly:
- **Token rules.** The last parameter takes the rest of the text when its schema is a string primitive. Other values
  take one non-whitespace token.
- **Value parsing:**
    - integer: `^-?[0-9]+$` within the safe range;
    - boolean: `true|yes|on` or `false|no|off`, case-insensitive;
    - user ID, room alias and server name: the Web regexes, with explicit ASCII classes and `Char.isWhitespace()` for
      `\s`;
    - `room_id` and `event_id` give null;
    - a literal must equal its value;
    - a union takes its first matching variant.
- **Rejection.** Arrays, or `room_id`/`event_id` anywhere in a schema, make the whole mapping null. So do a missing
  required parameter or leftover text.
- **Body.** The body is `"/${command}"` plus `" $argsText"` when the text is non-empty, using the canonical command as
  published.
- **Extra content.** `extraContentJson` is
  `{"org.matrix.msc4391.command":{"command":…,"arguments":{…}}}`, or null when the mapping is null.

`api` (T4):

```kotlin
data class SlashCommandSuggestion(
    val command: String,          // text inserted on selection (without the trailing space)
    val parameters: String?,
    val description: String,
    /** The bot offering this command, or null for a built-in. */
    val botUserId: UserId? = null,
)

/** Composer text resolved to a bot command published in the room, ready to send. */
data class BotCommandMessage(
    val botUserId: UserId,
    /** Plain canonical body: "/<command>" or "/<command> <args>". */
    val body: String,
    /** JSON object holding `org.matrix.msc4391.command`, or null when the arguments do not map cleanly. */
    val extraContentJson: String?,
)

interface SlashCommandService {
    /** Enabled built-ins (flag-gated, unchanged) followed by matching bot commands (always on). */
    suspend fun getSuggestions(text: String, isInThread: Boolean): List<SlashCommandSuggestion>
    // parse(...) unchanged: built-ins only.
    /**
     * Resolves plain composer text to a bot command; null when it is not one, a bare name belongs to an enabled
     * built-in, or a bare name is ambiguous. Never throws.
     */
    suspend fun resolveBotCommand(plainText: String): BotCommandMessage?
    /** Sends [message] on [timeline] mentioning only the bot, as a reply to [repliedToEventId] when set. */
    suspend fun sendBotCommand(message: BotCommandMessage, timeline: Timeline, repliedToEventId: EventId?): Result<Unit>
    // proceedSendMessage / proceedAdmin unchanged.
}
```

`impl/.../botcommands/RoomBotCommandsSource.kt` (T4) is `@SingleIn(RoomScope::class) @Inject`. It takes `JoinedRoom`:
- **Snapshot.** It holds
  `room.stateEventsFlow(StateEventType.Custom(BOT_COMMAND_DESCRIPTION_EVENT_TYPE)).stateIn(room.roomCoroutineScope, SharingStarted.Eagerly, emptyList())`.
- **Parse cache.** `parseBotCommandDescription` results are memoised by event ID, retaining only the current
  snapshot's IDs.
- **Logging.** It logs `Timber.d("Ignoring unusable bot command description from %s", sender)` once per event ID, with
  no content.
- **Reading commands.** `suspend fun commands(isBuiltIn: (String) -> Boolean): List<RoomBotCommand>` reads the latest
  snapshot and drops the session user's own events. For each distinct remaining sender it calls
  `room.getUpdatedMember(sender)`, which reads the store and syncs members once if needed. It keeps members with
  `JOIN`, using `disambiguatedDisplayName` (or the user ID) as `botName`. A lookup failure excludes that bot and logs
  `Timber.w(it, "…%s", userId)`.

`DefaultSlashCommandService` (T4):
- **`isBuiltIn(name)`.** True when `FeatureFlags.SlashCommand` is enabled and some `Command.entries` matches
  `"/$name"`. `CRASH_APP` counts only when developer mode is on. This mirrors `CommandParser`.
- **`getSuggestions`.** Returns the built-in list only when the flag is on, which removes today's early `return
  emptyList()`. It then appends `matchBotCommands(...)` mapped to `SlashCommandSuggestion`:
    - `command = insertedCommand`;
    - `parameters = formatBotCommandUsage(...).ifEmpty { null }`;
    - `description` from the new strings;
    - `botUserId` set.
- **`resolveBotCommand`.** Resolves against `RoomBotCommandsSource.commands(::isBuiltIn)`, then calls
  `createBotCommandMessage`. Any exception becomes null with `Timber.w(e, "Bot command resolution failed")`.
- **`sendBotCommand`.** Calls
  `timeline.sendPlainTextWithExtraContent(message.body, listOf(IntentionalMention.User(message.botUserId)), message.extraContentJson, repliedToEventId)`.

New strings in `libraries/slashcommands/impl/src/main/res/values/temporary.xml`, following the module's `slash_command_`
prefix:

```xml
<string name="slash_command_bot_description">%1$s (from %2$s)</string>
<string name="slash_command_bot_no_description">Command from %1$s</string>
```

`FakeSlashCommandService` (T4) gets lambdas for the two new methods.

### 2.5 Composer wiring (T5: `features/messages/impl`, `libraries/textcomposer/impl`)

`MessageComposerPresenter.sendMessage`, before the existing parse:

```kotlin
if (capturedMode is MessageComposerMode.Normal || capturedMode is MessageComposerMode.Reply) {
    val botCommand = slashCommandService.resolveBotCommand(currentComposerPlainText(markdownState, richState))
    if (botCommand != null) {
        val repliedTo = (capturedMode as? MessageComposerMode.Reply)?.eventId
        resetComposer(markdownState, richState, fromEdit = false)
        timelineController.invokeOnCurrentTimeline {
            slashCommandService.sendBotCommand(botCommand, this, repliedTo)
                .onFailure { Timber.e(it, "Failed to send bot command") }
        }
        // then the same notificationConversationService.onSendMessage + analytics Composer(...) capture as text sends
        return@launch
    }
}
// existing flow unchanged (Normal: built-in parse / error / plain send; Reply: replyMessage; Edit: editMessage …)
```

- **Send failures** follow the normal message path: the local echo shows the failure and the send queue retries.
- **Draft and history** handling is the same as for an ordinary send.
- **Plain text.**
    - Markdown mode uses the new `MarkdownTextEditorState.getMessagePlainText(): String` in `libraries/textcomposer`.
      It walks `getMentionSpans()` from the end and replaces each span with its user ID, room alias or `@room`.
    - Rich-text mode uses a new internal `htmlToComposerPlainText(html: String): String` in
      `features/messages/impl/.../messagecomposer/ComposerPlainText.kt`. It runs Jsoup `parseBodyFragment`, turns
      `<br>`, `<p>` and block boundaries into `\n`, and takes the visible text. Mention anchors already carry the user
      ID or alias as their text.
    - Neither mode emits Markdown escape markers. This follows Web's ruling of plain HTML-visible text over a Markdown
      converter.
- **`SuggestionsPickerView`.** The lazy-row key becomes `"${botUserId?.value.orEmpty()}|${command}"`. Rendering is
  unchanged: title = `command`, details = `parameters`, subtitle = `description`. Insertion is also unchanged: Markdown
  replaces the text with `"$command "`, and rich text uses `replaceSuggestion(command)`.
- **Preview.** The `SuggestionsPickerView` preview adds bot rows: a described row with usage, a no-description row and
  a qualified row. Previews use `@PreviewsDayNight` and `ElementPreview`. Baselines aren't recorded locally.
- **Typing notices, drafts and edit modes** are untouched.

### 2.6 Error handling, security, privacy

- **Malformed state** is dropped per event. No path throws into the composer: resolution catches and returns null, so
  the existing behaviour runs.
- **Nothing runs on the client.** A bot command only sends a visible message that the user typed. Enabled built-ins
  win for bare names, and the bot is always shown in the row.
- **Logs** carry only Matrix IDs, counts and exception types. Bodies, arguments and descriptions are never logged.
  Raw JSON is never logged either.
- **Membership lookups** are bounded by the number of distinct senders. The 500/1000 caps bound what is processed.
- **Encrypted rooms:** the extra content is merged before the send queue encrypts the event, so it's encrypted like
  the body.

### 2.7 Conventions

- New files get the repository header (`Copyright (c) 2026 Element Creations Ltd.` and
  `SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.`).
- Use KDoc on public API, `internal` for implementation types, Metro DI, Timber only, and the 160-char wrap.
- Tests use JUnit4, Truth, Turbine, `runTest` and the repository fakes.
- SDK code follows the fork's `rustfmt.toml` and clippy rules, with a CHANGELOG entry.

## 3. Tasks

The waves are ordered. Each task is cohesive and owns disjoint files. Run every Gradle and cargo command inside
`nix develop` from the worktree root.

### Wave 1 (T1 and T3 in parallel; disjoint files)

**T1 — Local AAR from the delivered SDK commit**

- **External prerequisite:** the SDK project's `SDK_READY <sha>` message (D8). The SDK code, its tests, clippy, nightly
  fmt and its delivery are owned and verified by that run, not by this plan. Don't start T1 before `SDK_READY`, and
  don't edit the SDK repo from this run.
- **Owns:**
    - the ignored clean clone `build/hermes-slash/matrix-rust-sdk`;
    - in the worktree: `tools/sdk/threads-inbox-sdk.md`, adding a paragraph saying the AAR also carries the MSC4391
      `required_state` and the reply FFI;
    - the ignored `libraries/rustsdk/matrix-rust-sdk.aar`, written atomically by copy-then-move.
- **Setup:** `git clone /home/sinkerine/dev/matrix-rust-sdk build/hermes-slash/matrix-rust-sdk`. Then
  `git -C build/hermes-slash/matrix-rust-sdk checkout -B main <sha>`, because the build script requires branch `main`.
  The clone holds no WIP.
- **Produces:** an x86_64 AAR exposing `RoomInterface.subscribeToStateEvents`, `TimelineInterface.sendWithExtraContent`
  and `sendReplyWithExtraContent`.
- **Depends on:** SDK_READY only. It runs in parallel with T3.
- **AAR build:**
  `THREADS_INBOX_SDK_CHECKOUT=$PWD/build/hermes-slash/matrix-rust-sdk tools/sdk/build-threads-inbox-sdk x86_64-linux-android`
- **Unit test (gate):** confirm the three symbols with
  `unzip -p libraries/rustsdk/matrix-rust-sdk.aar classes.jar > build/hermes-slash/classes.jar`, then
  `unzip -p build/hermes-slash/classes.jar org/matrix/rustcomponents/sdk/TimelineInterface.class | grep -a -c sendReplyWithExtraContent`
  (and the same for `RoomInterface.class` / `subscribeToStateEvents`). Then run
  `./gradlew :libraries:matrix:impl:compileDebugKotlin` to show the existing wrapper still compiles.

**T3 — Bot command protocol, resolution, matching and content (pure Kotlin)**

- **Owns:** `libraries/slashcommands/impl/src/main/kotlin/.../botcommands/{BotCommandProtocol,RoomBotCommands,BotCommandContent}.kt`,
  their tests under `impl/src/test/.../botcommands/`, and `libraries/slashcommands/impl/build.gradle.kts`, which gains
  `implementation(libs.serialization.json)`.
- **Consumes:** `UserId` only.
- **Produces:** the §2.4 internal functions and types. `BotCommandMessage` is defined in T4's API. To stay
  independent, T3's `createBotCommandMessage` returns an internal
  `BotCommandPayload(botUserId, body, extraContentJson)`, which T4 maps to the API type.
- **Tests:**
    - each validation accept and reject rule, including the MSC example, `{}` and a non-JSON string;
    - dedupe with newest-wins and a deterministic tie;
    - the 500 and 1000 caps;
    - localpart collision giving the full-ID qualifier;
    - built-in and two-bot clashes setting `requiresQualifier`;
    - qualified, full-ID and unknown-qualifier resolution;
    - ambiguity, longest multi-token match, aliases, `//` and NBSP whitespace;
    - matcher ordering, hyphens and `name@qual` filtering;
    - every schema mapping, including the null cases;
    - body and JSON shape with and without the block.
- **Depends on:** nothing.
- **Unit tests:**
  `./gradlew :libraries:slashcommands:impl:testDebugUnitTest --tests '*botcommands*'`
- **Bootstrap:** Gradle configuration needs some AAR, so do the §4.0 bootstrap copy before running T3 in parallel with
  T1.

### Wave 2

**T2 — Matrix wrapper: state flow and extra-content send**

- **Owns:**
    - `libraries/matrix/api/.../room/{RoomStateEvent.kt,JoinedRoom.kt}` and `.../timeline/Timeline.kt`;
    - `libraries/matrix/impl/.../room/JoinedRustRoom.kt` (plus a `RoomStateEvent` mapper file) and
      `.../timeline/RustTimeline.kt`;
    - `libraries/matrix/impl/src/test/.../fixtures/fakes/{FakeFfiRoom,FakeFfiTimeline}.kt`, plus new tests
      `JoinedRustRoomStateEventsTest.kt` and additions to `RustTimelineTest.kt`;
    - `libraries/matrix/test/.../room/FakeJoinedRoom.kt` and `.../timeline/FakeTimeline.kt`.
- **Consumes:** the T1 AAR.
- **Produces:** the §2.3 APIs.
- **Tests:**
    - mapping, including dropping an invalid sender;
    - flow emission and cancellation (the `TaskHandle` is destroyed);
    - the send path picks `sendWithExtraContent` versus `sendReplyWithExtraContent(addMentions=false)` with the exact
      body, no formatted body, the mentions and the extra JSON;
    - failures are wrapped in `Result`.
- **Depends on:** T1.
- **Unit tests:** `./gradlew :libraries:matrix:impl:testDebugUnitTest :libraries:matrix:test:testDebugUnitTest`

### Wave 3

**T4 — Slash command service wiring, strings and fake**

- **Owns:**
    - `libraries/slashcommands/api/.../{SlashCommandSuggestion,SlashCommandService}.kt` and a new
      `BotCommandMessage.kt`;
    - `libraries/slashcommands/impl/.../{DefaultSlashCommandService.kt, botcommands/RoomBotCommandsSource.kt}` and
      `res/values/temporary.xml`;
    - `impl/src/test/.../{DefaultSlashCommandServiceTest.kt, botcommands/RoomBotCommandsSourceTest.kt}`;
    - `libraries/slashcommands/test/.../FakeSlashCommandService.kt`.
- **Consumes:** T2 and T3.
- **Produces:** the §2.4 API.
- **Tests:**
    - built-ins then bot rows with the flag on;
    - bot rows only with the flag off;
    - developer-mode `/crash`;
    - precedence for flag on and off;
    - self, left and failed-lookup members excluded;
    - live snapshot updates and blanking;
    - parse-cache retention;
    - exact `sendPlainTextWithExtraContent` arguments with and without a reply;
    - resolution errors becoming null;
    - row descriptions taken from the strings.
- **Depends on:** T2, T3.
- **Unit tests:** `./gradlew :libraries:slashcommands:impl:testDebugUnitTest :libraries:slashcommands:test:testDebugUnitTest`

### Wave 4

**T5 — Composer: plain text, send branch, picker key and previews**

- **Owns:**
    - `features/messages/impl/.../messagecomposer/{MessageComposerPresenter.kt, ComposerPlainText.kt}` and
      `.../suggestions/SuggestionsPickerView.kt` (row key and previews);
    - their tests: `MessageComposerPresenterTest.kt`, `ComposerPlainTextTest.kt` and `SuggestionsProcessorTest.kt`
      (bot rows pass through);
    - `libraries/textcomposer/impl/.../model/MarkdownTextEditorState.kt` and its test.
- **Consumes:** the T4 API and `FakeSlashCommandService`.
- **Tests:**
    - Normal and Reply modes in the main timeline and in a thread call `sendBotCommand` with the right timeline and
      reply ID, then reset the composer and skip the built-in parse;
    - a `null` resolution keeps today's flows (built-in, error, plain and reply);
    - Edit and EditCaption never call `resolveBotCommand`;
    - Markdown and rich text give plain text with user IDs, aliases, `@room` and newlines, and no escapes;
    - picker keys are unique for a built-in and a qualified bot row with the same name;
    - Kover thresholds still hold for the presenter.
- **Depends on:** T4.
- **Unit tests:** `./gradlew :features:messages:impl:testDebugUnitTest :libraries:textcomposer:impl:testDebugUnitTest`

### Wave 5

**T6 — End-to-end journey (§5)**

- **Owns:** ignored `build/e2e/hermes-slash/**`: harness scripts, the stand-in bot, evidence and the report. It also
  owns an optional tracked Maestro flow, `.maestro/tests/hermesSlash/hermesSlashJourney.yaml`, which is **not** wired
  into `allTests.yaml`. The flow is included only if it adds stable repeatable coverage.
- **Consumes:** the T1–T5 behaviour.
- **Depends on:** T5.
- **Unit test gate:** the §4 step 1 focused command. T6 adds no unit tests.

## 4. Test and verification

### 4.0 Environment and setup the orchestrator must provide

Report each of these as a blocker if it's unavailable. Nothing is installed outside the worktree during design.

- **Shell.** `nix develop` from the worktree root provides JDK 21, compile SDK 37, build tools 37.0.0, stable Rust,
  cargo-ndk and NDK 27.2. `java` and `adb` are absent from the plain PATH.
- **Bootstrap AAR.** Builds fail at configuration without `libraries/rustsdk/matrix-rust-sdk.aar`. Before T3, copy the
  main checkout's existing AAR (`/home/sinkerine/dev/element-x-android/libraries/rustsdk/matrix-rust-sdk.aar`,
  2026-10-26) into this worktree's ignored path. It was checked to contain `stateEvents`, `subscribeToStateEvents` and
  `sendWithExtraContent`, but not the new reply FFI. T1 replaces it.
- **SDK build.** The build script clones `matrix-rust-components-kotlin` at a pinned revision, which needs network
  access. A clean SDK build of `matrix-sdk-ffi` takes a long time and a lot of disk space in the clone's `target/`.
- **SDK checks.** The SDK's own checks, including the `nightly-2026-09-28` rustfmt (not installed here), belong to the
  separate SDK project run (D8). This plan only consumes its delivered commit.
- **E2E tools.** The dedicated host SDK `~/.local/share/element-x-e2e/android-sdk` has `emulator`, `platform-tools` and
  the API 33 google_apis x86_64 image, with AVD home `~/.local/share/element-x-e2e/avds`.
    - Maestro is under `~/.local/share/element-x-e2e/maestro`; check `--version` before use.
    - The emulator runs under `steam-run`.
    - Docker has `matrixdotorg/synapse@sha256:1909bcc0…`, and Node is on PATH.
    - Orca isn't installed, so control goes through `adb -s <serial>`, UIAutomator dumps and Maestro, following the
      E2E skill's non-Orca path.

### 4.1 Commands (worktree root, inside `nix develop`)

1. **Focused feature tests:**
   `./gradlew :libraries:slashcommands:impl:testDebugUnitTest :libraries:slashcommands:test:testDebugUnitTest :libraries:matrix:impl:testDebugUnitTest :libraries:matrix:test:testDebugUnitTest :features:messages:impl:testDebugUnitTest :libraries:textcomposer:impl:testDebugUnitTest`
2. **Full unit suite,** as in `tests.yml`:
   `./gradlew :app:testGplayDebugUnitTest testDebugUnitTest -x :tests:konsist:testDebugUnitTest`, then
   `./gradlew :tests:konsist:testDebugUnitTest`.
3. **Coverage:** `./gradlew :koverXmlReportMerged :koverHtmlReportMerged :koverVerifyAll -x testDebugUnitTest`
4. **Compilation:** `./gradlew :app:compileGplayDebugKotlin :app:compileFdroidDebugKotlin`
5. **Lint:** `./gradlew :app:lintGplayDebug :app:lintFdroidDebug lintDebug --continue`
6. **Static checks:** `./gradlew detekt`, `./gradlew ktlintCheck` and `./gradlew checkDocs`.
7. **Screenshots:** `./gradlew verifyPaparazziDebug`. The changed `SuggestionsPickerView` preview has no committed
   baseline, and baselines must not be recorded locally (AGENTS.md). Its missing-baseline or diff failure is expected
   and is reported by name, alongside every other screenshot test passing. A future PR would carry
   `Record-Screenshots`. This is reported, not waived. Any other failure is a defect.
8. **Build:** `./gradlew :app:assembleGplayDebug` produces `app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk`.
9. **SDK:** cited from the SDK project's delivery report, by the commit named in `SDK_READY`. Here, T1's symbol gate
   proves the AAR was built from it.
10. **Pre-existing failures.** Anything already failing on base `0aa71ce4877a`, such as detekt, Konsist or LFS assets,
    must be shown to fail identically on the base with logs. It is reported, never silently excluded.
11. **E2E (§5).** This is required for this first delivery of the feature. It isn't a push or deploy requirement. The
    existing unit suites keep running on every push.

## 5. E2E journey (dedicated emulator, dedicated Synapse, stand-in bots)

### Setup

The harness is an ignored copy under `build/e2e/hermes-slash/`. It is parameterised from
`tools/interactive-ui/sandbox/{synapse-up,synapse-down,avd-up}.sh`, so it never reuses another feature's container,
port, AVD or app data:

- container `element-x-hermes-slash-synapse`, server name `hermes-slash.test`, port `127.0.0.1:18038`;
- AVD `hermes_slash_a1` (API 33, google_apis, x86_64, pixel_7_pro) on `emulator-5594`, started through `steam-run`;
- `adb -s emulator-5594 reverse tcp:18038 tcp:18038`.

Accounts are registered with the shared secret. Credentials go in an owner-only file outside git and are never
printed. The accounts are:

- `@alice` (the app user);
- `@hermes` (display name "Hermes");
- `@helper` (display name "Helper").

The stand-in bot is a dependency-free Node script using the Client-Server API (`fetch`). It can:

- create an **unencrypted** room "Hermes Room" with power levels giving hermes, helper and alice 50 for state, and
  invite alice, who joins through the API;
- `PUT /state/org.matrix.msc4391.command_description/<base64 sha256(command+mxid)>`;
- send messages, and leave;
- read `/messages` (dir=b) and `/state/m.room.topic` for **wire inspection**.

Install with `adb -s emulator-5594 install -r --no-incremental <x86_64 APK>`, then log in as alice through the
homeserver URL. Every step waits for sync with a bounded deadline, and evidence goes to
`build/e2e/hermes-slash/<timestamp>/`: UIAutomator XML, screenshots, `wire/*.json` and a redacted log audit.

### Steps

1. **Publish.**
    - Hermes publishes:
        - `model` (optional string `name`, "Show or change the model");
        - `help` (no parameters);
        - `arxiv` (optional string `args`, "Search arXiv papers"; a skill);
        - `reload-mcp`;
        - `topic` (optional string `text`);
        - `status`;
        - `oldcmd`, which is then blanked to `{}`;
        - a malformed event with `command: "bad token!"`.
    - Helper publishes `status` and `deploy` ("Deploy a build").
    - Alice publishes `selfcmd`.

    **Expect:** every PUT returns 200, and the bot sees the events in `/state`.
2. **Full list (flag off).** Alice opens the room and types `/`.
   **Expect:** no built-in rows, then, in this order:
    - `/deploy` "Deploy a build (from Helper)";
    - `/status@helper`;
    - `/arxiv [args]`;
    - `/help`;
    - `/model [name]` "Show or change the model (from Hermes)";
    - `/reload-mcp`;
    - `/status@hermes`;
    - `/topic [text]`.

    There's no `/oldcmd`, `bad`, or `/selfcmd` row.
3. **Fuzzy and complete.** Alice types `/mod`, which shows the `/model` row, and taps it.
   **Expect:** the composer reads `/model `. She types `gpt-5.5 --provider openrouter` and sends.
   **Expect:**
    - no error dialog, and the timeline shows the message, sent rather than pending;
    - the wire event matches AC6 exactly: body, `m.mentions` holding only hermes, the command block, and no `format`.
4. **Hyphen and skill.**
    - `/reload-m` suggests `/reload-mcp`. Selecting it and sending gives body `/reload-mcp`, command `reload-mcp` and
      arguments `{}`.
    - `/arxiv transformers attention` gives `arguments.args = "transformers attention"`.
    - `/arxiv ` plus an `@Helper` mention pill gives body `/arxiv @helper:hermes-slash.test`, with `m.mentions`
      holding hermes only.
5. **Qualifier and ambiguity (flag off).**
    - `/status@helper` sends body `/status` with mentions `[@helper]`.
    - `/status@hermes:hermes-slash.test` sends to hermes.
    - A bare `/status` and `/notacommand` are each sent as an ordinary message with no command block and no bot
      mention. This is today's behaviour, checked on the wire.
6. **Native collision (flag on).** Alice enables developer mode and the "Parse slash commands" feature flag, and
   records the change for restoring later. Typing `/top` shows the native `/topic` row first, then `/topic@hermes`.
    - `/topic Native topic` sets the room topic (`m.room.topic` sent by alice) and sends no `m.room.message`.
    - `/topic@hermes hello` sends body `/topic hello` with `arguments.text = "hello"`.
    - A bare `/status` shows the "Unrecognized command: /status" error. After dismissing it the composer still reads
      `/status`, and no event is sent.
    - Alice then turns the flag off again.
7. **Reply.** Helper sends "hi". Alice replies to it with `/model gpt-5`.
   **Expect:** `m.relates_to.m.in_reply_to.event_id` is helper's event, `m.mentions` holds **only** hermes, and the
   command block has `name: "gpt-5"`.
8. **Thread.** Alice opens a thread on the step 3 message and sends `/model`.
   **Expect:** `rel_type: "m.thread"` with the root's event ID, mentions holding only hermes, and command `{model, {}}`.
   Replying to an in-thread message with `/help` gives the thread relation, `m.in_reply_to` set to that message, and
   `is_falling_back: false`.
9. **Edit.** Alice edits her `/arxiv transformers attention` message into `/arxiv vision`.
   **Expect:** one `m.replace` event whose `m.new_content` has no `org.matrix.msc4391.command`, and no new standalone
   message.
10. **Rich text.** Alice turns on text formatting, types `/arx`, selects `/arxiv`, types `vision` and sends.
    **Expect:** body `/arxiv vision` with no `formatted_body`, and `args: "vision"`. Whether the rich-text editor's
    native slash pattern spans `-` and `@` is checked here with `/reload-m`; the result is recorded either way. Alice
    then turns formatting off.
11. **Freshness.**
    - Hermes republishes `model` as "Pick a model", and `/mod` shows "Pick a model (from Hermes)".
    - Hermes blanks `model`, and `/mod` shows no Hermes row.
    - Hermes leaves the room, and `/` then shows only Helper's rows, with `/status` now unqualified.
    - Sending `/arxiv x` gives an ordinary message with no command block.
12. **Privacy audit.** A synthetic canary argument from step 4 appears zero times in a bounded app-PID logcat sample.
    Only counts are reported.

### Cleanup

- Restore the feature flag, developer mode and formatting state.
- Remove the adb reverse.
- Stop and remove only `element-x-hermes-slash-synapse`, checking ownership by its label.
- Shut down `emulator-5594`, leaving every other AVD and installation untouched.
- Report passed, failed or blocked for each step, with artifact paths. Never claim a step that was blocked.

### Follow-up (H2, nonblocking)

Run the same journey against a real Hermes gateway once it publishes descriptions.

## 6. Open notes

- MSC4391 identifiers are unstable. The parser ignores unknown fields.
- **Android adaptations from Web:**
    - no rows appear while arguments are typed, because suggestions are word-scoped;
    - unknown and ambiguous commands use the native send or error path, since there's no dialog;
    - there's no native `/help`, so a bare `/help` reaches the bot;
    - built-ins win only when the native flag is on;
    - dedupe ties are made deterministic.
- **Bot authority.** Bots need power to send state before they can advertise commands. That's a property of the room,
  not this feature.
- **Fork dependency.** The SDK change lives in the fork. A future upstream SDK bump must keep the MSC4391
  `required_state` and the reply FFI, or bot commands will silently disappear.
- **Encrypted rooms.** The E2E uses an unencrypted room for wire inspection. Encryption of extra content is covered by
  the SDK send queue and not separately exercised on device.
