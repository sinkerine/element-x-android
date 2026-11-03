# Thread: "Your messages" jump list

> **Status: APPROVED by the user on 2026-11-02.** The user chose the **jump list with the mini-FAB entry (M1)**, kept the **press-and-hold preview** after it was explained (showcase §①b), and approved this plan as written, including the 3-PR / 3-wave split.
>
> Showcases (self-contained HTML in this folder; all content synthetic):
> - **Chosen design:** [`thread-my-messages-jumplist.html`](thread-my-messages-jumplist.html). It has an interactive Android mock (§①), a comparison with the Element Web source (§②), placements (§③) and trade-offs (§④).
> - Earlier explorations, kept for reference: [`thread-my-messages-showcase.html`](thread-my-messages-showcase.html). It covers the modal sheet, the full-screen list (5B), the contextual stepper (5A) and the M3 sheet (5C).
>
> Origin: an adaptation of Element Web's approved `thread-prev` design ("J1 without the ↑ button"). Web's approval does **not** imply approval for Android.

## 1. Goal and acceptance criteria

**Goal.** In an open thread (`ThreadedMessagesNode`), the user can open a list of every message *they* sent in that thread. They can jump to any of them with the list still open, preview a message without jumping, and step to their *previous* message relative to what they're reading. This includes messages older than what was loaded when the thread opened.

**Acceptance criteria**

| # | Criterion | Verified by |
|---|---|---|
| AC1 | **Entry.** A mini FAB (44dp, the current user's avatar with a list badge, content description "Your messages in this thread") sits directly above the existing jump buttons in the thread timeline's bottom-right corner. It shows only in thread timelines, and only when an own listable message is loaded, the root is the user's, or the thread was opened from the Threads inbox (which lists only participated threads). It appears without a reload once the user's first reply arrives. It is hidden while the list is open. | Presenter test (visibility); view test; E2E 2, 9 |
| AC2 | **Panel.** Tapping the FAB hides the keyboard and the composer, and docks a **non-modal** panel at the bottom: 45% of the screen height, at least 240dp. The thread timeline shrinks above it and stays visible, scrollable and interactive. The draft text in the composer is preserved. | View/node test; E2E 3 |
| AC3 | **Rows.** Newest first. Each row shows the time (`DateFormatter` `TimeOrDate`), a one-line snippet, a **Root** chip for the thread root, a green **on-screen dot** while the message is visible in the timeline, and an accent bar plus tint for the **current** jump target. The row for the "↑" target carries a "Previous" label. | Presenter + view tests; E2E 3, 5 |
| AC4 | **What's listed.** Events where `isOwn`, `eventId != null` and the content is `MessageContent` (any message type, including location), `StickerContent` or `PollContent`. This excludes redacted, unsent or failed local echoes, UTD, state and other events. Edits and reactions are aggregated by the SDK, so they never appear as items. Edited messages show their latest content. The thread root is included if the user sent it. | Presenter test; E2E 3 |
| AC5 | **Header and count.** "Your messages · N+" while older history may exist, "Your messages · N" once the start of the thread is reached. The header has ↑ ("Jump to your previous message") and ✕ ("Close"). | Presenter + view tests; E2E 3 |
| AC6 | **Progressive loading.** While the panel is open it paginates the thread's live timeline backwards (50 events per page) until the start, with a safety cap of **50 pages**. Rows appear as pages arrive. There is a linear progress bar while loading. The footer shows "Loading older messages…", "Start of thread", or "Couldn't load older messages" + **Retry**. Closing pauses the loop and reopening resumes it. Only one loop runs at a time. Android thread timelines are always at the live end, so no forward loading is needed. | Presenter tests (each terminal state, pause/resume, single-flight); E2E 3, 10 |
| AC7 | **Jump.** Tapping a row sends the existing `TimelineEvent.FocusOnEvent(eventId)`. The message is centered in the shrunk timeline with the existing focus highlight. The **panel stays open**, the row becomes current, and the jump is announced politely ("Your message from {time}: {snippet}"). If the event is no longer loaded, the timeline stays put and a snackbar says "That message is no longer available." | Presenter test; E2E 4 |
| AC8 | **Previous.** The anchor is the current target if it's still on screen, otherwise the oldest (top-most) event visible in the timeline. "Previous" is the newest listed message strictly older than the anchor in timeline order. On open, the list scrolls to and labels that row. ↑ jumps to it, and each further ↑ steps to the next older listed message. If none is loaded yet and loading continues, ↑ waits for the loop and retries once. If none exists and the list is complete, a snackbar says "No earlier messages from you in this thread" and nothing scrolls. | Presenter tests; E2E 5 |
| AC9 | **Preview.** Press and hold a row to preview it. Releasing ends the preview. If the message is **off screen**, a card appears above the panel. It shows the user's avatar, "You · {full time}", "(edited)" and a "Thread root" chip where relevant, the full text clamped to **8 lines**, and "After {sender}: {snippet}" from the loaded event before it. If the message is **on screen**, the card is not shown and the timeline tile gets an accent outline instead. TalkBack gets a "Preview" custom action that announces the same content. | Presenter + view tests; E2E 6 |
| AC10 | **Closing.** ✕, a downward drag on the panel's handle, or system Back (predictive back closes the panel before the thread) closes the panel and restores the composer. Scrolling or tapping the timeline does **not** close it. Back from the thread with the panel closed behaves as today. | View/node test; E2E 7 |
| AC11 | **Accessibility.** Each row's merged semantics read "{full time}[, thread root][, on screen], {snippet ≤ 80 chars}", with the current row marked selected. Opening announces "Your messages in this thread, {N or N+}". All targets are ≥ 48dp. | View tests |
| AC12 | **Privacy and logging.** Timber logs page counts, totals and event IDs only. Bodies and snippets are never logged. | Review |
| AC13 | **Size.** Each PR adds ≤ 500 production lines (AGENTS.md). See §3 for the split. | `git diff --stat` |

**Out of scope:** the Web marker rail (J2: targets too small for touch, and it conflicts with edge-to-edge back gestures). The side pane for expanded widths (J3) is a later follow-up. Also out of scope: a hardware-keyboard shortcut (optional follow-up: Alt+PageUp → ↑), messages outside threads, and cross-thread lists. When the existing ⌄ "jump to bottom" button is used, the current-target mark stays. Web clears it, but the Android timeline doesn't expose that event to the panel.

## 2. Design

### 2.1 Chosen UI

See the interactive mock in `thread-my-messages-jumplist.html` §①. It maps to Compound/Material components:

- **Mini FAB:** a `Surface` (shape `RoundedCornerShape(14.dp)`, `ElementTheme.colors.bgCanvasDefault`, elevation like the jump buttons). It contains `Avatar(ownAvatar)`, where the presenter builds `ownAvatar` with a new `AvatarSize.ThreadMyMessagesButton(28.dp)` entry (T2 adds it to `AvatarSize.kt`, a one-line change), and an 18dp badge circle (`iconAccentPrimary`) with `CompoundIcons.ListBulleted()`. It's inserted into the timeline's bottom-end jump-button column, above the jump-to-unread and jump-to-bottom buttons.
- **Panel:** a `Surface` with top corners 24dp and `bgCanvasDefault`, a 32×4dp drag handle, and a header `Row`: title `fontBodyLgMedium` plus the count in `textSecondary`, then `IconButton(CompoundIcons.ArrowUp())` and `IconButton(CompoundIcons.Close())`. Below the header: a `LinearProgressIndicator` while loading, a `LazyColumn` of rows, and a footer.
- **Row:** a `Row` (min height 48dp, padding 16/11dp). It holds the time (`fontBodySmRegular`, `textSecondary`, tabular), the snippet (`fontBodyMdRegular`, 1 line, ellipsis) with an optional Root chip, and an 8dp on-screen dot (`iconAccentPrimary`). The current row gets a `bgAccentSubtle` background and a 4dp start bar in `borderAccentSubtle`.
- **Preview card:** a `Surface` with 16dp corners, a 1.5dp `borderAccentSubtle` border and elevation, positioned 10dp above the panel. The on-screen outline is a 2dp `borderAccentSubtle` border drawn by `TimelineItemRow` for `outlinedEventId`.

### 2.2 Data source: the thread's own live timeline

The SDK thread timeline (`CreateTimelineParams.Threaded`) only paginates **backwards from the latest reply**; no event-focused thread timeline exists. In a thread, `TimelinePresenter` can only scroll to an event that is already loaded.

So the list is derived from **the same `Timeline` instance the thread view displays** (`ThreadedMessagesNode.ownedThreadTimeline`). Loading paginates that timeline, so every listed row is already in the displayed timeline and a jump is always an indexed `FocusOnEvent`. A message that was outside the loaded window when the thread opened becomes reachable because the panel loads it first. The cost is that a fully loaded long thread stays in memory (up to 50 pages, about 2,500 events). That's the same as the user scrolling all the way up.

### 2.3 Module boundaries and files

All production changes are in `features/messages/impl`, except one new `AvatarSize` entry in `libraries/designsystem`. No API, SDK or new module dependencies (`dateformatter`, `eventformatter` and `matrix` are already dependencies, including their `test` modules).

```
features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/
  threads/mymessages/
    ThreadMyMessagesState.kt          state, item, peek, load state                       (T1, extended by T3)
    ThreadMyMessagesEvent.kt          UI events                                           (T1, extended by T3)
    ThreadMyMessagesPresenter.kt      presenter + AssistedFactory                         (T1, extended by T3)
    OwnThreadMessages.kt              filter + whole-thread loader                        (T1)
    ThreadMyMessagesButton.kt         mini FAB                                            (T2)
    ThreadMyMessagesPanel.kt          panel, header, rows, footer                         (T2, extended by T3)
    ThreadMyMessagesPreviewCard.kt    preview card                                        (T3)
    ThreadMyMessagesStateProvider.kt  PreviewParameterProvider                            (T2, extended by T3)
  threads/ThreadedMessagesNode.kt     wiring                                              (T2, extended by T3)
  MessagesView.kt                     hooks: composer hiding, floating action slot        (T2; outline + visible ids T3)
  timeline/TimelineView.kt            hooks: floating action slot                         (T2; outline + visible ids T3)
  timeline/components/TimelineItemRow.kt  outline for outlinedEventId                    (T3)
  res/values/temporary.xml            strings                                             (T1, T2, T3 add their own)
```

### 2.4 Interfaces (exact signatures; "T3" marks members added in T3)

```kotlin
// ThreadMyMessagesState.kt
data class ThreadMyMessagesState(
    val isEntryVisible: Boolean,
    val ownAvatar: AvatarData,
    val isOpen: Boolean,
    val items: ImmutableList<ThreadMyMessageItem>,   // newest first
    val isComplete: Boolean,                         // start of thread reached
    val loadState: ThreadMyMessagesLoadState,
    val currentEventId: EventId?,                    // last jump target
    val pendingJump: EventId?,                       // one-shot, consumed by the node
    val previousEventId: EventId?,                   // T3: target of ↑
    val peek: ThreadMyMessagePeek?,                  // T3
    val eventSink: (ThreadMyMessagesEvent) -> Unit,
)

data class ThreadMyMessageItem(
    val eventId: EventId,
    val time: String,                 // DateFormatter(ts, TimeOrDate, useRelative = true)
    val fullTime: String,             // DateFormatter(ts, Full, useRelative = true)
    val snippet: String,              // first line of body
    val body: String,                 // PinnedMessagesBannerFormatter output; never logged
    val isRoot: Boolean,              // eventId == threadRootId.asEventId()
    val isEdited: Boolean,            // MessageContent.isEdited / PollContent.isEdited
    val isOnScreen: Boolean,          // T3 (false until then)
    val context: ThreadMyMessageContext?, // T3: loaded event just before it
)

data class ThreadMyMessageContext(val senderName: String, val snippet: String) // senderName = "You" for own

sealed interface ThreadMyMessagePeek {
    val eventId: EventId
    data class Card(override val eventId: EventId) : ThreadMyMessagePeek
    data class Outline(override val eventId: EventId) : ThreadMyMessagePeek
}

enum class ThreadMyMessagesLoadState { Idle, Loading, Failed }

// ThreadMyMessagesEvent.kt
sealed interface ThreadMyMessagesEvent {
    data object Open : ThreadMyMessagesEvent
    data object Close : ThreadMyMessagesEvent
    data object Retry : ThreadMyMessagesEvent
    data class JumpTo(val eventId: EventId) : ThreadMyMessagesEvent
    data object JumpConsumed : ThreadMyMessagesEvent
    data object JumpToPrevious : ThreadMyMessagesEvent                                    // T3
    data class Peek(val eventId: EventId) : ThreadMyMessagesEvent                        // T3
    data object EndPeek : ThreadMyMessagesEvent                                          // T3
    data class VisibleEventsChanged(val newestFirst: ImmutableList<EventId>) : ThreadMyMessagesEvent // T3
}

// ThreadMyMessagesPresenter.kt
@AssistedInject
class ThreadMyMessagesPresenter(
    @Assisted private val timeline: Timeline,
    @Assisted private val threadRootId: ThreadId,
    @Assisted private val openedFromThreadsInbox: Boolean,
    private val matrixClient: MatrixClient,                     // userProfile → ownAvatar
    private val summaryFormatter: PinnedMessagesBannerFormatter,
    private val dateFormatter: DateFormatter,
    private val snackbarDispatcher: SnackbarDispatcher,
) : Presenter<ThreadMyMessagesState> {
    @AssistedFactory
    fun interface Factory {
        fun create(timeline: Timeline, threadRootId: ThreadId, openedFromThreadsInbox: Boolean): ThreadMyMessagesPresenter
    }
    @Composable override fun present(): ThreadMyMessagesState
}

// OwnThreadMessages.kt
internal const val MY_MESSAGES_MAX_PAGES = 50

/** Own listable events with their chronological position in [this]. */
internal fun List<MatrixTimelineItem>.ownThreadMessages(): List<IndexedValue<EventTimelineItem>>

/** Paginates backwards until the start or [maxPages]; returns true when the start was reached. Rethrows cancellation. */
internal suspend fun loadWholeThread(timeline: Timeline, maxPages: Int = MY_MESSAGES_MAX_PAGES): Result<Boolean>

// ThreadMyMessagesButton.kt / Panel / PreviewCard
@Composable internal fun ThreadMyMessagesButton(avatar: AvatarData, onClick: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun ThreadMyMessagesPanel(state: ThreadMyMessagesState, modifier: Modifier = Modifier)
@Composable internal fun ThreadMyMessagesPreviewCard(item: ThreadMyMessageItem, avatar: AvatarData, modifier: Modifier = Modifier) // T3

// MessagesView.kt: new trailing parameters
fun MessagesView(
    ...,
    isComposerHidden: Boolean = false,
    threadFloatingActions: @Composable ColumnScope.() -> Unit = {},
    outlinedEventId: EventId? = null,                                          // T3
    onVisibleEventIdsChange: ((ImmutableList<EventId>) -> Unit)? = null,       // T3
    customReactionBottomSheet: @Composable () -> Unit,
)

// TimelineView.kt: new trailing parameters (forwarded by MessagesViewContent)
fun TimelineView(
    ...,
    floatingActions: @Composable ColumnScope.() -> Unit = {},                  // rendered above the jump buttons
    outlinedEventId: EventId? = null,                                          // T3
    onVisibleEventIdsChange: ((ImmutableList<EventId>) -> Unit)? = null,       // T3: newest first, partially visible included
)
```

### 2.5 Data flow

1. `ThreadedMessagesNode.createPresenter()` also creates `myMessagesPresenter = factory.create(threadedTimeline, inputs.threadRootEventId, inputs.triage)` and stores it in a `mutableStateOf` field.
2. `View()` renders:
   ```
   Box {
     Column {
       MessagesView(modifier.weight(1f) [+ consumeWindowInsets(navigationBars) when open], isComposerHidden = my.isOpen,
                    threadFloatingActions = { if (my.isEntryVisible && !my.isOpen) ThreadMyMessagesButton(my.ownAvatar) { Open } }, ...)
       if (my.isOpen) ThreadMyMessagesPanel(my, Modifier.height(panelHeight).navigationBarsPadding())
     }
     // T3: preview card aligned BottomCenter, bottom padding = panelHeight + 10.dp, when peek is Card
   }
   BackHandler(enabled = my.isOpen) { Close }
   LaunchedEffect(my.pendingJump) { id -> timelineEventSink(FocusOnEvent(id)); my.eventSink(JumpConsumed) }
   ```
   `panelHeight = max(240.dp, 0.45 × BoxWithConstraints.maxHeight)`.
3. Presenter items: it collects `timeline.timelineItems`, applies `ownThreadMessages()` and maps to `ThreadMyMessageItem` on the computation dispatcher. `isEntryVisible = openedFromThreadsInbox || items.isNotEmpty() || isOpen`. `isComplete` is read from `backwardPaginationStatus.hasMoreToLoad == false`.
4. `Open` → `isOpen = true`, announce, and start the loading job if `!isComplete` and no job is active. `Close` → `isOpen = false` and cancel the job (pause). `Retry` → restart the job.
5. The loading job runs `loadWholeThread(timeline)`: `Loading` → `Idle`, or `Failed` on error (with `Timber.w`).
6. `JumpTo(id)` → if `timeline.isEventLoaded(id)` is true: set `currentEventId = id` and `pendingJump = id`, then announce. Otherwise post the `jump_failed` snackbar.
7. T3: `VisibleEventsChanged(ids)` updates `isOnScreen` and the anchor. The anchor is `currentEventId` if visible, else `ids.last()`. `previousEventId` is the newest item whose chronological index is below the anchor's index. `JumpToPrevious` uses `currentEventId` as the base when set. `Peek(id)` gives `Outline` if visible, else `Card`. `EndPeek` clears the peek. `onVisibleEventIdsChange` is only passed to `TimelineView` while the panel is open.

### 2.6 Errors, conventions, subtleties

- A pagination error becomes `Failed` + Retry, and rows stay usable. Jump errors become a snackbar. There are no dialogs, and `FocusRequestStateView` is never triggered because jumps only target loaded events.
- `loadWholeThread` waits for `backwardPaginationStatus.first { !it.isPaginating }` before each page, because the timeline's own scroll prefetch may be paginating concurrently. It stops when `hasMoreToLoad` is false or the cap is hit.
- The composer is removed from composition while the panel is open. The draft lives in `MessageComposerState`/rich-text state, but a test must confirm the draft survives the open/close cycle (T2).
- `FocusOnEvent` can arrive before `TimelinePresenter`'s indexer has a freshly paginated event. `TimelinePresenter` already re-resolves the index when items change, so don't add another retry. The node's existing `focusedEventId` `LaunchedEffect` is independent.
- `onVisibleEventIdsChange` uses `snapshotFlow { layoutInfo.visibleItemsInfo }`. It maps item keys to event IDs (the timeline has non-event items) and applies `distinctUntilChanged`.
- Compound components and tokens only. Previews use `@PreviewsDayNight` + `ElementPreview` with `ThreadMyMessagesStateProvider` covering: loading with rows, failed, complete, empty-loading, current + previous, and (T3) preview card. Screenshots are recorded by CI; the PRs get the `Record-Screenshots` label.
- New strings go in `features/messages/impl/src/main/res/values/temporary.xml` (§2.7). Existing strings are reused: `CommonStrings.common_you`, `common_edited_suffix`, `action_close`, `action_retry`.

### 2.7 Strings

| Key | en | Task |
|---|---|---|
| `screen_thread_my_messages_title` | Your messages | T1 |
| `screen_thread_my_messages_count_more` | %1$d+ | T1 |
| `screen_thread_my_messages_jump_failed` | That message is no longer available. | T1 |
| `a11y_thread_my_messages_opened` | Your messages in this thread, %1$s | T1 |
| `a11y_thread_my_messages_jumped` | Your message from %1$s: %2$s | T1 |
| `a11y_thread_my_messages_jumped_root` | Your message from %1$s, thread root: %2$s | T1 |
| `a11y_thread_my_messages_open` | Your messages in this thread | T2 |
| `screen_thread_my_messages_root_chip` | Root | T2 |
| `screen_thread_my_messages_loading` | Loading older messages… | T2 |
| `screen_thread_my_messages_start_of_thread` | Start of thread | T2 |
| `screen_thread_my_messages_load_error` | Couldn’t load older messages | T2 |
| `screen_thread_my_messages_empty` | You haven’t posted in this thread yet | T2 |
| `a11y_thread_my_messages_root` | thread root | T2 |
| `a11y_thread_my_messages_previous` | Jump to your previous message | T3 |
| `screen_thread_my_messages_previous_label` | Previous | T3 |
| `screen_thread_my_messages_no_earlier` | No earlier messages from you in this thread | T3 |
| `screen_thread_my_messages_thread_root` | Thread root | T3 |
| `screen_thread_my_messages_context` | After %1$s: %2$s | T3 |
| `a11y_thread_my_messages_on_screen` | on screen | T3 |
| `a11y_thread_my_messages_preview` | Preview | T3 |

### 2.8 Implementation rulings after T1 review

- T1 exposes `announcement: String?` and `ThreadMyMessagesEvent.AnnouncementConsumed`. T2 delivers opening/jump announcements through a polite Compose live region, consuming each announcement after UI delivery. The presenter does not invoke Android View accessibility APIs. Repeated identical announcements must remain deliverable.
- The 50-successful-page budget and loading mutex live on the presenter instance and survive composition replacement, panel close/reopen, and Retry. `loadWholeThread` adds optional `onPageLoaded: () -> Unit = {}`; only successful pages initiated by this loader count. Contention (`CannotPaginate`) retries through the status wait with a cancellable 100ms backoff and consumes no budget.
- `ThreadMyMessagesState.isLimitReached` distinguishes capped incomplete history from completion and failure. T2 adds a "Loading limit reached" footer string, keeps the N+ count at the cap, and offers no ineffective Retry at the cap. T3 must not wait indefinitely for a previous message when capped or failed.
- T1 injects `CoroutineDispatchers` for computation mapping. It temporarily uses `AvatarSize.CurrentUserTopBar`; T2 owns replacing this with `AvatarSize.ThreadMyMessagesButton(28.dp)` in the presenter as well as adding the size entry.
- T1 accepted after two review rounds: 27 tests pass; no deferred findings. Production additions: 302 lines. T2 and T3 keep their independent <=500 production-line budgets; creating PRs remains subject to the final delivery gate.

## 3. Tasks

Three waves, one task each. Each wave lands as **its own PR** to stay within the 500-line production budget (estimates: T1 ≈ 250, T2 ≈ 330, T3 ≈ 300). The user was told two PRs earlier; the detailed estimate needs three. Production line counts include previews.

### Wave 1

**T1 — Jump-list state, filtering and whole-thread loading** *(subtle: concurrent pagination, single-flight job with pause/resume, one-shot jump)*
- Goal: the presenter for open/close, items, count, loading, Retry, jump and entry visibility, fully unit-tested. No UI.
- Owned files: `threads/mymessages/ThreadMyMessagesState.kt`, `ThreadMyMessagesEvent.kt`, `ThreadMyMessagesPresenter.kt`, `OwnThreadMessages.kt`; `res/values/temporary.xml`; tests `src/test/.../threads/mymessages/ThreadMyMessagesPresenterTest.kt`, `OwnThreadMessagesTest.kt`.
- Consumes: `Timeline`, `MatrixClient.userProfile`, `PinnedMessagesBannerFormatter`, `DateFormatter`, `SnackbarDispatcher`.
- Produces: everything in §2.4 not marked T3, except the UI composables and hooks.
- Dependencies: none.
- Tests:
  - Filter: own vs other, remote vs local or failed, redacted, UTD, state, poll, sticker, location, edited.
  - Root flag, newest-first order, count `N+` vs `N`.
  - Loader: stops at start, at the cap, on error; waits while paginating; cancellation propagates.
  - Open starts the job, Close pauses it, reopen resumes it, single-flight, Retry after a failure.
  - JumpTo loaded → `pendingJump`, `currentEventId`, announce. JumpTo unloaded → snackbar.
  - Entry visibility: no own items, own item arrives, opened from the inbox.
- Command: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.threads.mymessages.*"`

### Wave 2

**T2 — Mini FAB, docked panel and thread wiring** *(subtle: inset handling when the panel replaces the composer; draft preservation)*
- Goal: a user-visible jump list. FAB, panel (header, count, progress, rows with Root chip and current mark, footer states), timeline floating-action slot, composer hiding, node wiring (Back, jump dispatch), previews.
- Owned files: `threads/mymessages/ThreadMyMessagesButton.kt`, `ThreadMyMessagesPanel.kt`, `ThreadMyMessagesStateProvider.kt`; `threads/ThreadedMessagesNode.kt`; `MessagesView.kt`; `timeline/TimelineView.kt`; `res/values/temporary.xml`; `libraries/designsystem/.../components/avatar/AvatarSize.kt` (one enum entry); tests `src/test/.../threads/mymessages/ThreadMyMessagesPanelTest.kt`, additions to `src/test/.../MessagesViewTest.kt` and `src/test/.../timeline/TimelineViewTest.kt`.
- Consumes: T1's state, events and factory; `TimelineEvent.FocusOnEvent`.
- Produces: `ThreadMyMessagesButton`, `ThreadMyMessagesPanel`, `MessagesView(isComposerHidden, threadFloatingActions)`, `TimelineView(floatingActions)`.
- Dependencies: T1.
- Tests:
  - Row tap → `JumpTo`, ✕ → `Close`, Retry → `Retry`.
  - Footer and empty texts per state, Root chip and current-row semantics.
  - FAB rendered in the floating column only when given. Composer absent when `isComposerHidden` and draft retained after toggling.
  - `MessagesViewTest`: floating slot shown in thread mode.
- Command: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.threads.mymessages.*" --tests "io.element.android.features.messages.impl.MessagesViewTest" --tests "io.element.android.features.messages.impl.timeline.TimelineViewTest"`

### Wave 3

**T3 — Reading position: on-screen dots, "previous" and preview** *(subtle: lazy-list visible range → event IDs in reverse layout; anchor rules; deferred previous while loading; press-and-hold gesture vs tap)*
- Goal: on-screen dots, the ↑ previous button with stepping and the no-earlier notice, press-and-hold preview (card or on-screen outline), TalkBack "Preview" action, and context lines.
- Owned files: `threads/mymessages/ThreadMyMessagesState.kt`, `ThreadMyMessagesEvent.kt`, `ThreadMyMessagesPresenter.kt`, `ThreadMyMessagesPanel.kt`, `ThreadMyMessagesPreviewCard.kt`, `ThreadMyMessagesStateProvider.kt`; `threads/ThreadedMessagesNode.kt`; `MessagesView.kt`; `timeline/TimelineView.kt`; `timeline/components/TimelineItemRow.kt`; `res/values/temporary.xml`; tests: `ThreadMyMessagesPresenterTest.kt`, `ThreadMyMessagesPanelTest.kt`, `ThreadMyMessagesPreviewCardTest.kt`, `TimelineViewTest.kt` additions.
- Consumes: T1 and T2 outputs.
- Produces: the T3 members in §2.4.
- Dependencies: T2.
- Tests:
  - Anchor = current if visible, else oldest visible; previous = newest strictly older.
  - Stepping older; deferred previous resolves after a page; no-earlier snackbar when complete.
  - Peek → Card off screen and Outline on screen; EndPeek.
  - On-screen flags follow `VisibleEventsChanged`.
  - Card shows the 8-line clamp, badges and context.
  - Long-press does not trigger `JumpTo`; the TalkBack custom action exists.
  - `TimelineView` reports visible event IDs, newest first, skipping virtual items, and draws the outline.
- Command: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.threads.mymessages.*" --tests "io.element.android.features.messages.impl.timeline.TimelineViewTest"`

## 4. Test and verification

```sh
# Feature unit tests
./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.threads.*" --tests "io.element.android.features.messages.impl.MessagesViewTest" --tests "io.element.android.features.messages.impl.timeline.TimelineViewTest"
# Whole module regression
./gradlew :features:messages:impl:testDebugUnitTest
# Style, static analysis, architecture
./gradlew ktlintCheck detekt
./gradlew :features:messages:impl:lintDebug
./gradlew :tests:konsist:testDebugUnitTest
# Build the app
./gradlew :app:assembleGplayDebug
# Per-PR production size (≤ 500 added lines)
git diff --stat <pr-base> -- '*/src/main/*'
```

Screenshot baselines are recorded by CI only (`Record-Screenshots` label), never locally.

## 5. E2E journey

Preconditions: install the debug APK (`:app:assembleGplayDebug`) on a test emulator per `.agents/skills/element-x-android-e2e/SKILL.md`. Use test account **A** (under test) and **B** in a dedicated test room. Seed one thread with synthetic text:
- A sends root "E2E root".
- B and A then post 150 replies. A's are "A-01" at #5, "A-02" at #20 (then edited to "A-02 edited"), "A-03" at #110 and "A-04" at #148. A also sends "A-del" at #60 and then deletes it. B posts everything else.

Seed a second thread where only B posts (≤ 30 replies). AGENTS.md has no sandbox E2E section yet; the orchestrator resolves that before the E2E gate.

1. As A, open the room → Threads → "E2E root". **Expect:** the latest replies are shown with A-04 near the bottom. A-01 and A-02 are not loaded.
2. **Expect:** a mini FAB with A's avatar and a list badge above the jump-to-bottom area, labelled "Your messages in this thread".
3. Tap the FAB. **Expect:** the composer disappears and a panel docks at the bottom. Its header reads "Your messages · 2+" with a progress bar, and the thread stays visible above. When loading completes the header reads "Your messages · 5" and the footer "Start of thread". Rows, newest first: A-04, A-03, A-02 edited, A-01, and "E2E root" with a **Root** chip. There is no A-del row and no B rows. A-04 shows a green on-screen dot.
4. Tap **A-01**, which was outside the loaded window when the thread opened. **Expect:** the timeline scrolls up and centers A-01 with the focus highlight. The panel **stays open**, the A-01 row is marked current and now shows the on-screen dot. B's replies #4 and #6 are visible around it.
5. Scroll the timeline down until A-03 is the top-most message by A that is visible, with no A message currently highlighted on screen, then tap **↑**. **Expect:** the timeline jumps to and highlights A-02 (the newest of A's messages above the reading position). Tap ↑ again. **Expect:** A-01. Tap ↑ again. **Expect:** "E2E root". Tap ↑ again. **Expect:** the snackbar "No earlier messages from you in this thread", and nothing scrolls.
6. Scroll so A-04 is off screen, then press and hold the **A-04** row. **Expect:** a card above the panel shows "You · {time}", the text "A-04" and "After B: …". Release. **Expect:** the card disappears and the timeline didn't move. Press and hold the row of a message currently on screen. **Expect:** no card; that tile is outlined.
7. Press system Back. **Expect:** the panel closes, the composer returns with any draft intact, and the timeline position is unchanged. Press Back again. **Expect:** the thread closes as today.
8. Reopen the thread and the panel. **Expect:** the count is exact immediately (history already loaded in this session) and the rows are unchanged.
9. Open the B-only thread from the room's Threads list. **Expect:** no mini FAB.
10. (Optional, if network control is available.) In a fresh session, open "E2E root", tap the FAB and cut the network during loading. **Expect:** the footer shows "Couldn't load older messages" with Retry, and the rows already loaded still jump. Restore the network and tap Retry. **Expect:** loading resumes to "Start of thread".
