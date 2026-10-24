# Threads Inbox SDK spike

Audited `org.matrix.rustcomponents:sdk-android:26.09.9-custom.1` using `javap` on its cached AAR on 2026-10-24.

Decision: use Option B, Kotlin aggregation, behind `CrossRoomThreadsService`. The task prohibits modifying the Rust SDK fork. Option A remains the release direction.

Evidence:

- `ThreadListItem` contains rootEvent, latestEvent and numReplies only. Neither it nor `ThreadListItemEvent` has unread counts, mention counts, participation or receipt IDs.
- `RoomInterface.loadUserReceipt(ReceiptType, ReceiptThread, userId)` returns `UserReceipt(eventId, receipt)`; `ReceiptThread.Thread(threadRootEventId)` is available.
- `RoomInterface.sendSingleReceipt(ReceiptType, ReceiptThread, eventId)` can send a threaded receipt directly without creating a timeline.
- `RoomInterface.fetchThreadSubscription(threadRootEventId)` exists, but subscriptions are not equivalent to own-event participation and may be unsupported by the server.
- Room unread and mention counters cannot safely be assigned to individual threads.

The prototype must cap active room subscriptions at 20, release them when the visit ends, and never infer thread mentions from room mention counts. Unread counts and participation remain approximate when only root/latest events are available. Quiet-room discovery and a 300-room memory benchmark require a seeded account. No emulator or AVD was listed by Orca at the time of the spike; no performance result is claimed.

Local raw signatures are retained under ignored `build/threads-inbox/ffi-audit.txt` and `ffi-details.txt`.
