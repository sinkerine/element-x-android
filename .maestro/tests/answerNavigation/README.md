# Answer navigation journey

This standalone flow exercises the default Android rich-text renderer in an already-open dedicated fixture room or thread. It does not clear application data, send messages, or belong to the destructive `allTests.yaml` suite.

Build the current worktree using `tools/gradle-local.sh :app:assembleGplayDebug`, then install the matching ABI APK with `adb -s <serial> install -r --no-incremental`. Coordinate builds and emulator testing using the shared common-Git-directory `e2e-feature-build.lock`. Follow `.agents/skills/element-x-android-e2e/SKILL.md` for environment, fixture, evidence and cleanup requirements.

The fixture must be a formatted text or notice message with more than 2,000 eligible rendered characters and these six top-level headings, in order:

1. Summary
2. Evidence
3. Trade-offs
4. Evidence
5. Sources
6. End

The fourth section's body starts with `SECOND EVIDENCE PASSAGE.`. The Sources section includes an author-labelled HTTP link `Matrix fixture server versions` pointing to `http://127.0.0.1:18038/_matrix/client/versions`. Provide the dedicated loopback fixture server and `adb -s <serial> reverse tcp:18038 tcp:18038`. The answer also contains a quoted heading named `Quoted heading`, which must not appear in the index. Keep the default renderer enabled (the developer-only alternate Compose renderer disabled).

Open the room/thread with a `Sections` action visible. Complete Chrome's first-run onboarding on the dedicated AVD before running the flow. Browser opening uses the application's existing external-link handling. Run:

```sh
maestro --device <serial> test \
  -e MAESTRO_APP_ID=io.element.android.x.debug \
  --test-output-dir build/e2e/answer-navigation/journey \
  .maestro/tests/answerNavigation/answerNavigationJourney.yaml
```

The flow checks the numbered index, explicit source label and host, quoted-heading exclusion, backward then forward navigation to distinguish duplicate headings, heading-to-index re-entry, dismissal, source opening, and return to the passage. It leaves the user in the fourth section. Exact before/after passage coordinates should also be captured during interactive verification, since seeing the same text alone does not prove reading-position preservation.

Repeat in a room and a thread, after an edit, and with a narrow viewport. Separately verify the initial `In this message` control, source-free answers, a short message without a control, and an edit that makes an eligible message short. Preserve existing sessions and use synthetic fixtures only. Keep credentials, screenshots, hierarchy dumps, and browser artifacts local under ignored `build/e2e/`; inspect before sharing.

On this host, direct SwiftShader GLES caused a native QEMU RenderThread SIGSEGV after onboarding. The supported `-gpu swangle -feature -Vulkan` configuration got past that crash while retaining the existing AVD data. Do not inherit emulator-specific `LD_LIBRARY_PATH` settings into Maestro's Java launcher.
