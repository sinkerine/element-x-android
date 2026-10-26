# Threads Inbox SDK integration

Threads Inbox uses `Client.loadThreadInbox` and `subscribeToThreadInboxUpdates` from the local Matrix Rust SDK fork's `main` branch. Android variants use the same locally built AAR so release compilation cannot silently select the older published bindings.

Build prerequisites: Rust 1.96 or later with the Android target installed, cargo-ndk, JDK 21, Android SDK and NDK. Set `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `ANDROID_NDK_HOME`. The default authorized checkout is `/home/sinkerine/orca/matrix-rust-sdk`; override it with `THREADS_INBOX_SDK_CHECKOUT` when reproducing elsewhere. The script requires the SDK checkout to be on `main` and pins the Kotlin packaging project to the revision used during verification.

```sh
tools/sdk/build-threads-inbox-sdk x86_64-linux-android
./gradlew :app:assembleGplayDebug
```

Run the SDK script again with `aarch64-linux-android` to add arm64 before building for an arm64 device. The staging directory retains the previously built ABI. The generated AAR and Kotlin packaging checkout are local build artifacts and must not be committed. No publishing or credentials are needed.

Discovery validates server joined-room membership and exhausts each room's thread-list pagination. Four concurrent room requests limit work in flight, not account coverage. A failed room/page prevents a successful complete snapshot. Android waits for initial sync and full history classification before publishing its first successful aggregate; later failures retain the previous snapshot with a retryable error. Sync invalidations include receipt-only updates, and lag requests full discovery. While the inbox is closed, updates use a compact badge classifier over threaded relations and receipts; this does not build UI timelines, sender profiles, previews, or observable lists. Opening refreshes any dirty list state; closing cancels an in-flight full refresh.

Unread counts and explicit mentions are classified from decrypted thread history using event order and threaded receipts. No server-wide unread-count API or atomic snapshot across concurrent server mutations is assumed. Updates received while discovery runs are queued for reconciliation. The UI never treats the first page or a bounded subset of rooms as a complete account total.
