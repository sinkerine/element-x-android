# Thread drawer: move the Threads inbox into an app-wide left drawer

> Status: **APPROVED by the user (2026-11-02).** All decisions are final, including D6 (Back from a drawer-opened thread lands on the origin page with the drawer closed) and D3 (the Threads button is on Home only; other screens use the swipe).
>
> Showcase (mockups, tradeoffs): https://claude.ai/artifact/3BqzyHbuvsyVMtFDd5D7nj

## Goal

Move the cross-room Threads inbox out of Home's floating bottom toolbar into a native-style left modal drawer that's available on every logged-in screen of Element X on phones. A left-to-right swipe opens it on any screen, with lower priority than swipe-to-reply on timeline messages. Home also has a Threads button for discoverability. Tapping outside the drawer, Back, × or a drag closes it, leaving you on the page you opened it from. A thread opened from the drawer behaves as today (focused unread event, Up next). Back from it returns you to the page you opened the drawer from, with the drawer closed.

The inbox itself is unchanged: rows, ordering, swipe-to-mark-read, the long-press menu, Mark loaded threads as read, Undo, paging, retry, and visited styling.

## Current state (base `ef91eb563c`)

- `HomeNavigationBarItem { Chats, Spaces, Threads }`. Threads is shown only when `FeatureFlags.CrossRoomThreads && FeatureFlags.Threads`. Selecting it calls `ThreadsTriageQueue.startVisit()`; leaving it calls `endVisit()`.
- `HomeFlowNode` hosts the inbox as the permanent child `NavTarget.ThreadsInbox` (`ThreadsInboxEntryPoint.createNode`). Its callback forwards to `HomeEntryPoint.Callback.navigateToThread/openThreadInRoom`. `LoggedInFlowNode` implements those by pushing `NavTarget.Room(initialElement = RoomNavigationTarget.Thread(...))`.
- The visit spans the whole triage session: opening a thread from the tab doesn't end it. `endVisit()` (via `ThreadsInboxSession.endVisit`) commits pending reads and clears `visited`, `openThread` (which Up next excludes), `touched` and the frozen order, then stops `CrossRoomThreadsService`'s visit. **Ending the visit while a drawer-opened thread is on screen would break Up next.**
- `ThreadsInboxPresenter` calls `queue.startVisit(); session.returnedToList()` on composition and `session.commit()` on dispose.
- The unread badge is deliberately off since 26.10.9 (`threadsUnreadCount = 0`).
- `LoggedInFlowNode.View` renders `BackstackView` plus the permanent `LoggedInPermanent` child (when FTUE is complete), inside `ElementThemeApp` and `ConnectivityIndicatorContainer`. The PIN unlock screen is a separate `PinUnlockActivity`, and calls run in `ElementCallActivity`, so neither can show the drawer.
- Competing horizontal gestures:
  - Timeline swipe-to-reply (`TimelineItemEventRow`, left-to-right).
  - Inbox rows (right-to-left).
  - The Home avatar account pager.
  - `HorizontalPager` in `MediaViewerView`, `AttachmentsPreviewView`, `MediaGalleryView` and `PollHistoryView`.
  - Map panning in `MapBottomSheetScaffold` (used by both show-location and share-location).
  - The emoji picker and reaction summary pagers live in bottom sheets (separate windows, so there's no conflict).

## Decisions

| ID | Decision | Answer |
| --- | --- | --- |
| D0 | Option and form factor | A, inbox drawer. Phones only: no tablet-specific layout or acceptance criteria. |
| D1 | Unread indicator | None (preserve 26.10.9). |
| D2 | Gestures | Native Android drawer. A left-to-right swipe anywhere opens it, and a scrim tap or drag closes it. Child gestures (swipe-to-reply, inbox row swipe, pagers, the avatar pager) take priority because they consume the drag first. Swiping is disabled on full-screen media, attachment preview and map screens. |
| D3 | Opener button | On Home's top bar, right after the avatar. Other screens rely on the swipe. |
| D4 | Back while the drawer is open | Closes the drawer only; you stay on the page you opened it from. |
| D5 | Availability | Every logged-in screen rendered by `LoggedInFlowNode`'s back stack, once FTUE is complete and both Threads flags are on. |
| D6 | Back from a thread opened via the drawer | You land on the page you opened the drawer from, with the drawer closed. The triage visit stays active until you're back there. |

Rejected alternatives: B (navigation-menu drawer; reworks Settings and account switching) and C (push-reveal and tablet dock; changes visit semantics and needs an adaptive layout). See the showcase.

## Acceptance criteria

1. Home's floating toolbar shows exactly Chats and Spaces; no Threads item exists.
2. With both flags on and FTUE complete:
   - a. Home's top bar shows a Threads icon button after the avatar (content description "Threads"). Tapping it opens the drawer.
   - b. On Home, in a room timeline, in a thread timeline, in Settings and in room details, a left-to-right swipe on a non-message area opens the drawer.
3. With either flag off (or before FTUE completes), there's no button, swiping never opens the drawer, and an open drawer closes, ending any active visit.
4. The drawer is an M3 `ModalDrawerSheet` (max 360dp) with the header "Threads" and a × button ("Close"), above the unchanged Threads inbox.
5. In a room or thread timeline, a left-to-right swipe on a message that supports reply starts a reply and doesn't open the drawer.
6. Inside the drawer, a right-to-left swipe on a row marks it read and leaves the drawer open. A right-to-left drag on the header or scrim closes the drawer.
7. Swiping doesn't open the drawer on the media viewer, the attachments preview, or the show-location and share-location maps.
8. Back, a scrim tap, × or a drag closes the drawer and leaves the underlying page, its scroll position and selection unchanged. Back closes the drawer even if the underlying page has its own enabled `BackHandler`, such as Home with a space filter selected.
9. Tapping a row closes the drawer and opens the thread (focused first unread, Up next) on top of the current page. Up next keeps working across threads. Back from the thread (or the last Up next thread) returns to the origin page with the drawer closed.
10. "Open in room" from the long-press menu closes the drawer and opens the thread in its room (`triage = false`) on top of the current page. Preserve the existing non-triage room stack: Back first reaches that room timeline, then the drawer origin; the visit ends on reaching the origin.
11. Visit lifecycle:
    - a. `ThreadsTriageQueue.startVisit()` is called once when the drawer opens with no active visit.
    - b. `endVisit()` is called exactly once, when the drawer is closed **and** no drawer-opened thread remains above the origin page.
    - c. Reopening the drawer during a triage session doesn't start a second visit.
    - d. Closing it during a triage session doesn't end the visit.
12. The inbox Composable is composed only while the drawer is open (or animating closed). It isn't composed while closed or during an uncommitted drag.
13. The drawer's open state and the triage origin survive configuration changes. After process death the drawer starts closed with no visit.
14. `./gradlew` unit tests for the affected modules, ktlint, detekt, lint and `:app:assembleGplayDebug` pass. Previews exist for the drawer open and closed and for the Home button. Screenshots are recorded by CI (`Record-Screenshots` label).

## Design

### Module boundaries

| Module | Change |
| --- | --- |
| `libraries/designsystem` | New generic gesture-blocking primitive (`BlockAppDrawerGestures.kt`) so libraries and features can disable drawer swipes without depending on threads. |
| `features/threads/api` | New `ThreadsDrawer` interface. `ThreadsInboxEntryPoint`, `ThreadsTriageQueue` and `ThreadsInboxBadgeProvider` are unchanged. |
| `features/threads/impl` | `DefaultThreadsDrawer` (state machine, visit ownership) and `ThreadsDrawerLayout` (UI). The inbox screen files are unchanged. Adds the `libraries.featureflag.api` dependency. |
| `features/threads/test` | `FakeThreadsDrawer`; `FakeThreadsTriageQueue` gains visit counters. |
| `appnav` | `LoggedInFlowNode` hosts the drawer around its `BackstackView`, owns the inbox permanent child, pushes threads opened from the drawer, and tracks the triage origin. |
| `features/home/api` + `impl` | Removes the Threads tab and the inbox hosting; adds the Threads button; drops `HomeEntryPoint.Callback.navigateToThread/openThreadInRoom`. |
| `libraries/mediaviewer/impl`, `features/messages/impl` (attachments preview), `features/location/impl` | Call `BlockAppDrawerGestures()`. |
| Strings | None new. Reuses `CommonStrings.screen_home_tab_threads` ("Threads") and `CommonStrings.action_close`. |

### Interfaces (exact signatures)

`libraries/designsystem/src/main/kotlin/io/element/android/libraries/designsystem/utils/BlockAppDrawerGestures.kt`

```kotlin
/** Counts on-screen requests to disable the app drawer's open swipe. */
@Stable
class AppDrawerGestureBlocker {
    val isBlocked: Boolean
    fun block()
    fun unblock()
}

val LocalAppDrawerGestureBlocker: ProvidableCompositionLocal<AppDrawerGestureBlocker?> // staticCompositionLocalOf { null }

/** While composed, disables swipe-to-open of the enclosing app drawer. No-op without a provider. */
@Composable
fun BlockAppDrawerGestures()
```

`features/threads/api/src/main/kotlin/io/element/android/features/threads/api/ThreadsDrawer.kt`

```kotlin
interface ThreadsDrawer {
    /** True when FeatureFlags.CrossRoomThreads and FeatureFlags.Threads are both enabled. */
    val isEnabled: StateFlow<Boolean>
    val isOpen: StateFlow<Boolean>

    /** Identity of the active session visit; null after it ends or resets. Not restored after process death. */
    val visitId: StateFlow<UUID?>

    /** Shows the drawer; starts a triage visit if none is active. No-op when disabled or already open. */
    fun open()

    /** Hides the drawer; ends the visit unless a thread opened from the drawer is still above the origin page. */
    fun close()

    /** A thread was opened from the drawer: hides the drawer and keeps the visit active. */
    fun onThreadOpened()

    /** The origin page is active again (or gone): ends the visit if the drawer is closed. */
    fun onReturnedToOrigin()

    /** Wraps [content] in the modal drawer; [inbox] renders the inbox. [isAvailable] gates the drawer (e.g. FTUE complete). */
    @Composable
    fun Layout(
        isAvailable: Boolean,
        inbox: @Composable (Modifier) -> Unit,
        modifier: Modifier,
        content: @Composable () -> Unit,
    )
}
```

`features/threads/impl/.../DefaultThreadsDrawer.kt`

```kotlin
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultThreadsDrawer(
    private val queue: ThreadsTriageQueue,
    featureFlagService: FeatureFlagService,
    client: MatrixClient,
) : ThreadsDrawer
```

State machine (private fields `visitIdState: MutableStateFlow<UUID?>`, `threadOnStack`; `isOpen` is a `MutableStateFlow`):

| Call | Effect |
| --- | --- |
| `open()` | If unavailable, `!isEnabled.value` or open, no-op. If `visitId.value == null`: `queue.startVisit(); visitIdState.value = UUID.randomUUID()`. Then `isOpen = true`. |
| `close()` | If not open, no-op. `isOpen = false`. If `!threadOnStack`: `endVisitIfActive()`. |
| `onThreadOpened()` | If `visitId.value == null`, no-op. Otherwise `isOpen = false; threadOnStack = true`. The visit is unchanged. |
| `onReturnedToOrigin()` | If `!threadOnStack`, no-op. `threadOnStack = false`. If not open: `endVisitIfActive()`. |
| `isEnabled` becomes false (collected in `client.sessionCoroutineScope`) | `isOpen = false; threadOnStack = false; endVisitIfActive()`. |
| `setAvailable(isAvailable: Boolean)` (internal) | Updates availability; when unavailable, resets open/thread state and ends the active visit once. |
| `endVisitIfActive()` | If `visitId.value != null`: `visitIdState.value = null; queue.endVisit()` (exactly once). |

`isEnabled = combine(flag(CrossRoomThreads), flag(Threads)) { a, b -> a && b }.stateIn(client.sessionCoroutineScope, SharingStarted.Eagerly, false)`.

`Layout` collects `isEnabled` and `isOpen`, applies `SideEffect { setAvailable(isAvailable) }`, then calls `ThreadsDrawerLayout(isEnabled = isAvailable && enabled, isOpen = isAvailable && enabled && open, onOpen = ::open, onClose = ::close, inbox, modifier, content)`.

`features/threads/impl/.../ThreadsDrawerLayout.kt`

```kotlin
@Composable
internal fun ThreadsDrawerLayout(
    isEnabled: Boolean,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    inbox: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
```

Behavior:

- The layout is always a `ModalNavigationDrawer`, never conditionally unwrapped, so toggling flags never recreates the app's back stack UI.
- `val blocker = remember { AppDrawerGestureBlocker() }`; `content` is provided with `LocalAppDrawerGestureBlocker provides blocker`.
- `val drawerState = rememberDrawerState(initialValue = if (isOpen) Open else Closed, confirmStateChange = { if (it == Open) currentOnOpen() else currentOnClose(); true })`. The callbacks are read through `rememberUpdatedState`. Gesture settles and scrim taps go through this, and the controller guards make repeated calls harmless.
- `LaunchedEffect(isOpen) { if (isOpen) drawerState.open() else drawerState.close() }`.
- `gesturesEnabled = isEnabled && (isOpen || !blocker.isBlocked)`.
- `drawerContent = { ModalDrawerSheet(Modifier.semantics { paneTitle = title }) { … } }`, where the sheet contains:
  - A header row: the title `screen_home_tab_threads` (`fontHeadingSmMedium`) and an `IconButton(onClose)` showing `CompoundIcons.Close()` with content description `action_close`.
  - `if (isOpen || (drawerState.isAnimationRunning && drawerState.targetValue == Closed)) inbox(Modifier.fillMaxSize())`.
  - `if (isOpen) BackHandler(onBack = onClose)`. This handler is registered when the drawer opens, so it is the newest registration and beats any page `BackHandler` composed earlier.

`features/threads/test`:

```kotlin
class FakeThreadsDrawer(
    override val isEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
    override val isOpen: MutableStateFlow<Boolean> = MutableStateFlow(false),
    override val visitId: MutableStateFlow<UUID?> = MutableStateFlow(null),
) : ThreadsDrawer {
    val calls: MutableList<String> // "open", "close", "onThreadOpened", "onReturnedToOrigin"
    // Mirrors visit identity across open/close/thread/return calls; Layout renders content() only
}

// FakeThreadsTriageQueue: add `var startVisitCount = 0` and `var endVisitCount = 0` (endVisit keeps clearing visited).
```

`appnav/.../LoggedInFlowNode.kt`:

- New constructor dependencies: `threadsDrawer: ThreadsDrawer`, `threadsInboxEntryPoint: ThreadsInboxEntryPoint`.
- `NavTarget`: add `@Parcelize data object ThreadsInbox : NavTarget`; `permanentNavModel.navTargets = setOf(NavTarget.LoggedInPermanent, NavTarget.ThreadsInbox)`.
- `resolve(NavTarget.ThreadsInbox)` returns `threadsInboxEntryPoint.createNode(this, buildContext, callback)`, where:
  - `openThread(key, focusedEventId, focusCaptureFailed)` → `openFromDrawer(NavTarget.Room(key.roomId.toRoomIdOrAlias(), initialElement = RoomNavigationTarget.Thread(key.threadId, focusedEventId, focusCaptureFailed = focusCaptureFailed)))`
  - `openRoom(key)` → `openFromDrawer(NavTarget.Room(key.roomId.toRoomIdOrAlias(), initialElement = RoomNavigationTarget.Thread(key.threadId, null, triage = false)))`
- `private val drawerOrigin = ThreadsDrawerOrigin(threadsDrawer, backstack, buildContext.savedStateMap)` owns navigation coordination.
- `onSaveInstanceState(state)` delegates to `drawerOrigin.saveInstanceState(state)`.
- `private fun openFromDrawer(target: NavTarget)` delegates to `drawerOrigin.openThread(target)`.
- `onBuilt()` calls `drawerOrigin.observe(lifecycleScope)`.
- `View`: inside the existing `Box(contentModifier)`:

  ```kotlin
  threadsDrawer.Layout(
      isAvailable = ftueState is FtueState.Complete,
      inbox = { m -> Box(m) { PermanentChild(permanentNavModel, NavTarget.ThreadsInbox) } },
      modifier = Modifier.fillMaxSize(),
  ) { BackstackView(...) }
  ```

  `LoggedInPermanent` stays a sibling, unchanged.
- The `HomeEntryPoint.Callback` implementation loses `openThreadInRoom` and `navigateToThread`.

`appnav/.../loggedin/ThreadsDrawerOrigin.kt`

```kotlin
/** True when [origin] is the active element again, or no longer in the back stack. */
internal fun <T : Any> List<BackStackElement<T>>.hasReturnedTo(origin: NavKey<T>): Boolean
// = none { it.key == origin } || lastOrNull { it.targetState == BackStack.State.ACTIVE }?.key == origin

internal class ThreadsDrawerOrigin<T : Any>(
    private val drawer: ThreadsDrawer,
    private val backstack: BackStack<T>,
    savedState: SavedStateMap?,
) {
    fun saveInstanceState(state: MutableSavedStateMap)
    fun openThread(target: T)
    fun observe(scope: CoroutineScope)
}
```

**Ruling — minimal visit identity extension (accepted Important T2 repair):** `ThreadsDrawer.visitId: StateFlow<UUID?>` is the sole API addition. It replaces the controller's private active Boolean with a fresh, non-restored UUID per visit, retained across configuration changes and drawer reopen/Up next. A Boolean reset notification cannot distinguish an old visit from a new one when `StateFlow` conflates reset/reopen emissions. Appnav saves the origin key together with its visit ID (`threads_drawer_origin`, `threads_drawer_origin_visit`) and invalidates ownership whenever the saved/captured ID differs from the controller's current ID, including a fresh controller after process death. It validates before navigation, saving and observer handling; observers read current backstack/controller values instead of queued snapshots. `openThread` captures the active key, calls `onThreadOpened`, pushes the thread, then publishes new ownership so an immediate observer cannot mistake the still-active origin for a return. No suspension occurs in this sequence. The fake mirrors active visit identity; controller tests cover reset/new identities, and integration tests exercise the production coordinator with `DefaultThreadsDrawer` and `BackStack`.

**Ruling — swipe-to-reply priority repair (device verification):** The timeline's existing reply gesture multiplies touch slop by three. The drawer's default touch slop let it claim a slow drag before the reply gesture became eligible, despite child-first event dispatch. Both now use `HORIZONTAL_SWIPE_TOUCH_SLOP_MULTIPLIER = 3f` and `SwipeSensitivity` from designsystem. The drawer restores the original `LocalViewConfiguration` for both page content and inbox, preserving their gesture sensitivity and giving the timeline child first opportunity at the equal threshold. Drawer activation requires slightly more movement. A regression renders the actual `TimelineItemEventRow` inside `DefaultThreadsDrawer.Layout` and moves in small increments to cross the original parent threshold before the reply threshold; it fails before the repair and passes afterward. Non-message opening, message long press, pager priority, inbox row priority and cancelled/interrupted drawer gestures are covered as well.

**Ruling — preserved Open in room Back behavior:** D6 and criterion 9 describe triage thread navigation. For criterion 10, `MessagesFlowNode` already installs the room timeline beneath a non-triage thread. Preserve this behavior: Back reaches that room first, then the origin. Do not alter the messages stack to force a single Back to the origin.

`features/home/api/.../HomeEntryPoint.kt`: remove `navigateToThread(...)` and `openThreadInRoom(...)` from `Callback`.

`features/home/impl`:

- `HomeNavigationBarItem { Chats, Spaces }`. `from(index)` maps an unknown index (the old Threads = 2) to Chats.
- `HomeEvent`: add `data object OpenThreadsDrawer : HomeEvent`.
- `HomeState`: add `val isThreadsDrawerOpen: Boolean`. `showThreads` keeps its name. `isBackHandlerEnabled` is unchanged apart from the Threads case going away.
- `HomePresenter`:
  - Replace the `threadsQueue: ThreadsTriageQueue` constructor dependency with `threadsDrawer: ThreadsDrawer`.
  - `showThreads = threadsDrawer.isEnabled.collectAsState()`.
  - `isThreadsDrawerOpen = threadsDrawer.isOpen.collectAsState()`.
  - `OpenThreadsDrawer -> threadsDrawer.open()`.
  - `SelectHomeNavigationBarItem` only changes the ordinal.
  - The badge stays 0.
- `HomeTopBar(..., showThreadsButton: Boolean, isThreadsDrawerOpen: Boolean, onThreadsClick: () -> Unit, modifier: Modifier = Modifier)`.
  - `navigationIcon = Row { NavigationIcon(...); if (showThreadsButton) ThreadsDrawerButton(isThreadsDrawerOpen, onThreadsClick) }`.
  - The button shows `ThreadsSolid` with the `iconAccentPrimary` tint when open, otherwise `Threads` with `iconSecondary`. Its content description is `screen_home_tab_threads` and its test tag is `TestTags.homeThreadsButton`.
  - The Threads title branch is removed.
- `HomeView`:
  - Remove the `threadsView` parameter.
  - `HomeBottomBar` iterates all entries with no Threads parameters, and the FAB is always present.
  - Remove the Threads content and scroll branches.
- `HomeFlowNode`:
  - Remove the `threadsInbox` dependency, `NavTarget.ThreadsInbox`, the `permanentNavModel` argument (default) and the `threadsView` wiring.
  - Keep `threadsQueue` for `setForeground`.
- `libraries/testtags`: `val homeThreadsButton = TestTag("home-threads_button")`.

### Data flow

```
Home Threads button ─OpenThreadsDrawer─▶ HomePresenter ─▶ ThreadsDrawer.open() ─startVisit─▶ queue
Swipe → on any page ─▶ ModalNavigationDrawer settle Open ─confirmStateChange─▶ ThreadsDrawer.open()
ThreadsDrawer.isOpen ─▶ LoggedInFlowNode.View → ThreadsDrawerLayout ─▶ composes PermanentChild(ThreadsInbox)
                                                                     └ ThreadsInboxPresenter: startVisit(no-op)/returnedToList … commit on dispose
Back / scrim / × / drag ─▶ ThreadsDrawer.close() ─(no thread on stack)─ endVisit ─▶ queue
Row tap ─▶ ThreadsInboxNode ─queue.open─▶ callback.openThread ─▶ LoggedInFlowNode.openFromDrawer:
           capture origin + visit ID; ThreadsDrawer.onThreadOpened() (drawer hides, visit kept); push Room(Thread); publish ownership
Up next ─▶ replaceTriageThread (origin stays below)
Back to origin (or origin removed) ─▶ backstack.elements.hasReturnedTo(origin) ─▶ ThreadsDrawer.onReturnedToOrigin() ─endVisit─▶ queue
```

### Errors and edge cases

- Inbox load and mark errors render inside the drawer exactly as today (snackbars, Retry).
- Flags turned off at any time: the controller closes the drawer and ends the visit. Gestures and the button disappear.
- Drag-open cancelled before settling: no `open()`, so no visit is started and no inbox is composed.
- The drawer is reopened while reading a drawer-opened thread: no second `startVisit`. Closing it again keeps the visit until you return to the origin.
- A notification or permalink clears the back stack and removes the origin: `hasReturnedTo` is true, so the visit ends.
- Configuration change: the controller (session singleton) keeps `isOpen`, `visitId` and `threadOnStack`; appnav restores the origin only when its saved visit ID matches the retained controller.
- Process death: the fresh controller starts closed with `visitId = null`; appnav discards any saved origin from the previous controller. A later visit captures its own origin.
- Dialogs and bottom sheets are separate windows, so the drawer neither opens over them nor steals their gestures.
- The Threads tab ordinal persisted by older versions maps to Chats.

### Conventions

Timber only; never log thread content (Matrix IDs only). No `localazy.xml` edits and no new strings. Compound icons and tokens. `@PreviewsDayNight` and `ElementPreview` previews for `ThreadsDrawerLayout` (closed, open with the inbox loaded) and Home with the button. No local screenshot recording: the PR gets the `Record-Screenshots` label. Metro DI: `@ContributesBinding(SessionScope::class)` and `@SingleIn`. 160-character lines. Estimated production additions: about 330 lines, within the 500 limit.

## Tasks

### Wave 1

**T1: Threads drawer component and visit ownership**

- Goal: provide the app-wide drawer building blocks. These are the generic gesture blocker, the `ThreadsDrawer` API, the `DefaultThreadsDrawer` state machine (sole owner of `startVisit`/`endVisit` for drawer and triage sessions), `ThreadsDrawerLayout` with previews, and the test fakes.
- Owned files:
  - `libraries/designsystem/src/main/kotlin/io/element/android/libraries/designsystem/utils/BlockAppDrawerGestures.kt` (new)
  - `features/threads/api/src/main/kotlin/io/element/android/features/threads/api/ThreadsDrawer.kt` (new)
  - `features/threads/impl/build.gradle.kts`
  - `features/threads/impl/src/main/kotlin/io/element/android/features/threads/impl/DefaultThreadsDrawer.kt` (new)
  - `features/threads/impl/src/main/kotlin/io/element/android/features/threads/impl/ThreadsDrawerLayout.kt` (new)
  - `features/threads/test/build.gradle.kts`
  - `features/threads/test/src/main/kotlin/io/element/android/features/threads/test/FakeThreadsDrawer.kt` (new)
  - `features/threads/test/src/main/kotlin/io/element/android/features/threads/test/FakeThreadsTriageQueue.kt`
  - `features/threads/impl/src/test/kotlin/io/element/android/features/threads/impl/DefaultThreadsDrawerTest.kt` (new)
  - `features/threads/impl/src/test/kotlin/io/element/android/features/threads/impl/ThreadsDrawerLayoutTest.kt` (new)
  - `libraries/designsystem/src/test/kotlin/io/element/android/libraries/designsystem/utils/AppDrawerGesturesTest.kt` (new)
- Consumes: `ThreadsTriageQueue.startVisit()/endVisit()`, `FeatureFlagService.isFeatureEnabledFlow`, `FeatureFlags.CrossRoomThreads/Threads`, `MatrixClient.sessionCoroutineScope`.
- Produces: `AppDrawerGestureBlocker`, `LocalAppDrawerGestureBlocker`, `BlockAppDrawerGestures()`, `ThreadsDrawer`, `FakeThreadsDrawer`, and `FakeThreadsTriageQueue.startVisitCount/endVisitCount`.
- Dependencies: none.
- Tests:
  - `DefaultThreadsDrawerTest`, covering every row of the state machine table:
    - open → start once.
    - close → end once.
    - thread opened → closed and the visit kept.
    - reopen during triage → no second start; close during triage → no end; returned to origin → end once.
    - open ignored when disabled; flags off while open or while triaging → closed and ended once.
  - `ThreadsDrawerLayoutTest` (Robolectric `runAndroidComposeUiTest`):
    - closed → the inbox isn't composed; open → header and inbox shown.
    - × → `onClose`; Back → `onClose` even when the content has an enabled `BackHandler`.
    - `swipeRight` on the content → `onOpen`.
    - with `BlockAppDrawerGestures()` in the content → no `onOpen`.
    - disabled → no `onOpen`.
    - a child horizontal `draggable` in the content consumes the swipe → no `onOpen`, which models swipe-to-reply priority.
  - `AppDrawerGesturesTest`: block/unblock counting, and a no-op without a provider.
- Unit test command: `./gradlew :features:threads:impl:testDebugUnitTest :libraries:designsystem:testDebugUnitTest`

### Wave 2 (T2 and T3 run concurrently; disjoint files; both depend only on T1)

**T2: Host the drawer app-wide and remove the Home tab**

- Goal:
  - Host `ThreadsDrawer.Layout` around `LoggedInFlowNode`'s back stack.
  - Move the inbox permanent child from Home to `LoggedInFlowNode`, open threads from the drawer, and track the triage origin.
  - Replace Home's Threads tab with the top bar button, and update the Maestro flow.
- Owned files:
  - `appnav/build.gradle.kts` (test-only dependencies on threads impl/test for the accepted origin repair integration coverage)
  - `appnav/src/main/kotlin/io/element/android/appnav/LoggedInFlowNode.kt`
  - `appnav/src/main/kotlin/io/element/android/appnav/loggedin/ThreadsDrawerOrigin.kt` (new)
  - `appnav/src/test/kotlin/io/element/android/appnav/loggedin/ThreadsDrawerOriginTest.kt` (new)
  - `features/home/api/src/main/kotlin/io/element/android/features/home/api/HomeEntryPoint.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeNavigationBarItem.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeEvent.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeState.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomePresenter.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeView.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeFlowNode.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/HomeStatePreviewParam.kt`
  - `features/home/impl/src/main/kotlin/io/element/android/features/home/impl/components/HomeTopBar.kt`
  - `libraries/testtags/src/main/kotlin/io/element/android/libraries/testtags/TestTags.kt`
  - `features/home/impl/src/test/kotlin/io/element/android/features/home/impl/HomePresenterTest.kt`
  - `features/home/impl/src/test/kotlin/io/element/android/features/home/impl/DefaultHomeEntryPointTest.kt`
  - `features/home/impl/src/test/kotlin/io/element/android/features/home/impl/HomeViewTest.kt` (new)
  - `.maestro/tests/threads/threadsInboxTriage.yaml`
- Consumes: `ThreadsDrawer` (T1), `ThreadsInboxEntryPoint.createNode/Callback`, `RoomNavigationTarget.Thread`, `replaceTriageThread` (unchanged), `FakeThreadsDrawer` (T1).
- Produces: `HomeEvent.OpenThreadsDrawer`, `HomeState.isThreadsDrawerOpen`, the new `HomeTopBar` parameters, `TestTags.homeThreadsButton`, `LoggedInFlowNode.NavTarget.ThreadsInbox`, `hasReturnedTo`, and the slimmer `HomeEntryPoint.Callback`.
- Dependencies: T1.
- Tests:
  - `ThreadsDrawerOriginTest`: origin active → true; origin stashed under a pushed thread → false; Up next replace keeps the origin below → false; origin removed → true. Integration coverage uses the actual `ThreadsDrawerOrigin` coordinator, controller and backstack: flag reset/re-enable with conflated observer emissions captures Settings and ends exactly once; fresh-controller restore discards the old origin; configuration restore retains the live origin through Up next; immediate observers do not end during push.
  - `HomePresenterTest`:
    - `OpenThreadsDrawer` → `FakeThreadsDrawer.calls == ["open"]`.
    - `showThreads` and `isThreadsDrawerOpen` mirror the fake's flows.
    - Navigation bar selection makes no drawer or queue calls.
    - The badge stays 0.
  - `HomeViewTest`: the button is absent when `showThreads = false`; tapping it emits `OpenThreadsDrawer`; the toolbar has no Threads item.
  - `DefaultHomeEntryPointTest`: compiles with the new constructor.
  - Maestro rewrite of `threadsInboxTriage.yaml` (see the E2E journey, steps 2, 5–7).
- Unit test command: `./gradlew :appnav:testDebugUnitTest :features:home:impl:testDebugUnitTest`

**T3: Disable the drawer swipe on conflicting full-screen gestures**

- Goal: call `BlockAppDrawerGestures()` in screens whose primary gesture is horizontal panning or paging, so the drawer never steals it.
- Owned files:
  - `libraries/mediaviewer/impl/src/main/kotlin/io/element/android/libraries/mediaviewer/impl/viewer/MediaViewerView.kt`
  - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/attachments/preview/AttachmentsPreviewView.kt`
  - `features/location/impl/src/main/kotlin/io/element/android/features/location/impl/common/ui/MapBottomSheetScaffold.kt`
  - `libraries/mediaviewer/impl/src/test/kotlin/io/element/android/libraries/mediaviewer/impl/viewer/MediaViewerDrawerGesturesTest.kt` (new)
- Consumes: `BlockAppDrawerGestures()` and `LocalAppDrawerGestureBlocker` (T1).
- Produces: nothing.
- Dependencies: T1.
- Tests: `MediaViewerDrawerGesturesTest`. It provides a blocker, renders `MediaViewerView` with a preview state, and asserts `isBlocked`; after disposal, it asserts not blocked. The location and attachment screens are covered by compile checks, their existing tests and the E2E journey.
- Unit test command: `./gradlew :libraries:mediaviewer:impl:testDebugUnitTest :features:messages:impl:testDebugUnitTest :features:location:impl:testDebugUnitTest`

## Test and verification

- Unit/UI (Robolectric):

  ```
  ./gradlew :features:threads:impl:testDebugUnitTest :libraries:designsystem:testDebugUnitTest :appnav:testDebugUnitTest \
    :features:home:impl:testDebugUnitTest :libraries:mediaviewer:impl:testDebugUnitTest :features:messages:impl:testDebugUnitTest \
    :features:location:impl:testDebugUnitTest
  ```

- Architecture tests: `./gradlew :tests:konsist:testDebugUnitTest`
- Format and static analysis: `./gradlew ktlintCheck detekt`
- Lint: `./gradlew :app:lintGplayDebug`
- Kotlin type checks and compile: `./gradlew :app:compileGplayDebugKotlin`
- Build: `./gradlew :app:assembleGplayDebug`
- Screenshots: don't record locally. The PR gets `Record-Screenshots`; the changes are expected only in Home and the new `ThreadsDrawerLayout` previews.
- Static checks:
  - `git grep -n "HomeNavigationBarItem.Threads"` and `git grep -n "navigateToThread(roomId" -- features/home` return nothing.
  - `git grep -n "startVisit()\|endVisit()" -- features/home` returns nothing.

## E2E journey

Preconditions:

- The gplay debug build (`io.element.android.x.debug`) is installed with `adb install -r --no-incremental` on a dedicated phone emulator.
- The test account is logged in and FTUE is complete.
- At least three unread participated threads exist across at least two rooms, plus one room with an image message and one with a location message.
- The Threads and CrossRoomThreads flags are on (the default).
- Follow AGENTS.md "End-to-end Feature Testing" and `.agents/skills/element-x-android-e2e/SKILL.md`; capture screenshots as evidence.

1. Launch on Home (Chats). Expected: the toolbar shows only Chats and Spaces; a Threads button follows the avatar, with no badge.
2. Tap the Threads button. Expected: the drawer slides in from the left over a scrim, with the header "Threads" and ×, then "Mark loaded threads as read" and the unread threads first.
3. Tap the scrim. Expected: the drawer closes and Chats is unchanged.
4. Swipe left-to-right across the middle of the room list. Expected: the drawer opens. Press Back. Expected: it closes, and Home stays on Chats.
5. Swipe a drawer row right-to-left. Expected: the "1 thread marked as read" snackbar with Undo appears and the drawer stays open. Tap Undo.
6. Tap the first unread row. Expected: the drawer closes and the thread opens with the first unread reply focused and "Next (n)". Tap Next. Expected: the next unread thread opens.
7. Press Back. Expected: Home (Chats) with the drawer closed.
8. Open a room. Swipe left-to-right on a message bubble. Expected: reply mode starts and the drawer stays closed. Swipe left-to-right on the top bar or composer area. Expected: the drawer opens over the room.
9. From the drawer opened in the room, tap a thread in another room. Expected: the thread opens. Press Back. Expected: the original room with the drawer closed, and its timeline position unchanged.
10. Long-press a drawer row and tap "Open in room". Expected: the drawer closes and the thread opens in its room. Press Back. Expected: its room timeline. Press Back again. Expected: the origin page, ending the visit.
11. Open Settings and swipe left-to-right. Expected: the drawer opens over Settings. Press Back. Expected: the drawer closes and Settings stays.
12. Open an image in the media viewer and swipe left-to-right. Expected: the viewer pages or does nothing; the drawer doesn't open. Do the same on a location map. Expected: the map pans; the drawer doesn't open.
13. Open the drawer and rotate the device or toggle dark mode. Expected: the drawer is still open.
14. Disable the Threads flag in developer settings and return. Expected: there's no Threads button, and swiping never opens the drawer.
15. Run the rewritten Maestro flow `.maestro/tests/threads/threadsInboxTriage.yaml`. Expected: it passes.
