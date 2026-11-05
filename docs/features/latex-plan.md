# LaTeX maths rendering in Element X Android — design plan

> Status: **APPROVED by the user (2026-11-05)**, v3. This includes render only, delimiter detection, both renderers
> with L1 fixed (0.4 scale floor), RaTeX, always on with no feature flag, and limitations L2–L6. Nothing is
> implemented.
>
> AGENTS.md "Feature Defaults" was updated in this worktree at the user's request (uncommitted): new features ship
> enabled and unflagged unless the user asks for a flag.
>
> Worktree `/home/sinkerine/.paseo/worktrees/3b36k2jo/freezing-blobfish`, branch `add-latex-rendering-support`, base
> `origin/develop` @ `5dfe456ef6db866dbd9b3ba5438e7c88509ea1bc`.
>
> Sibling contract (read-only): the Element Web LaTeX branch at `/home/sinkerine/.paseo/worktrees/0w88rorz/helpful-snail`
> (HEAD `6a3c2d193f`). Its `docs/features/latex-plan.md` was approved 2026-11-04. It turns `feature_latex_maths` on by
> default and fixes the delimiter rules reused in §2.6.

## Verified facts that shape the design

| Fact | How it was verified |
| --- | --- |
| Matrix maths markup is `<span data-mx-maths="TEX">fallback</span>` (inline) and `<div data-mx-maths="TEX">fallback</div>` (display). Element Web sends `<code>TEX</code>` as the fallback, for example `hello <span data-mx-maths="\xi"><code>\xi</code></span> world`. `body` keeps the typed `$…$` source. Web escapes a literal dollar as `\$` in `body`. | Web `apps/web/src/editor/serialize.test.ts:185-230`, `serialize.ts:95-99`, Web plan AC 2–3 |
| Android has no maths support today. The `Safelist` in `CustomHtmlToDomParser` has no `span`/`div` and no `data-mx-maths`, so `Jsoup.clean` unwraps the maths element and only the `<code>TEX</code>` fallback reaches the renderers. | `libraries/matrixui/.../messages/ToHtmlDocument.kt:70-100`; repo grep for `latex\|katex\|mx-maths` = 0 hits |
| Two renderers consume the same jsoup `Document`. **Legacy (the default):** `HtmlConverter.fromDocumentToSpans` (wysiwyg 2.42.1 `HtmlToSpansParser`) produces a `CharSequence` shown by `EditorStyledText` (a `TextView`). **Compose:** `HtmlMessageParser` → `DocumentNode` → `HtmlMessageContent`, used only when `FeatureFlags.NewTimelineEventRenderer` is on (default `false`, not in Labs). Plain-text messages (no `formatted_body`) always use legacy `EditorStyledText` with `pillify(body).safeLinkify()`. | `TimelineItemContentMessageFactory.kt:87-122,454-459`; `TimelineItemTextView.kt:54-104`; `FeatureFlags.kt:178-185` |
| The legacy `HtmlToSpansParser.parseElement` **drops unknown tags together with their text** (`else -> Timber.d("Unsupported tag")`). It treats `div` like `p`, collapses whitespace in text nodes, and has no hook for custom spans. | wysiwyg-2.42.1-sources `HtmlToSpansParser.kt:80-128` |
| Media captions in the timeline render through `CaptionView` → `EditorStyledText` from the same `parseHtml` output. | `TimelineItemEventContentView.kt:267-295` |
| Timeline content factories run on `dispatchers.computation`. | `TimelineItemsFactory.kt:71` |
| `htmlBody` (`htmlDocument.body().html()`) seeds the rich text editor when editing with formatting on. | `MessagesPresenter.kt:545-551`, `TimelineItemTextBasedContent.kt:43` |
| Sending uses the Rust SDK Markdown→HTML (`messageEventContentFromMarkdown`). | `libraries/matrix/impl/.../util/MessageEventContent.kt:50-57` |
| CommonMark turns a backslash before ASCII punctuation into the bare character. So a bot's Markdown `\(a\,b\)` arrives in `formatted_body` as `(a,b)`, and `\[ … \]` arrives as `[ … ]`. TeX taken from HTML text is lossy; `body` is the faithful source. | CommonMark spec §2.4 (backslash escapes) |
| **Renderer survey.** `ru.noties:jlatexmath-android:0.2.0` is GPL-2.0 (blocked by `licensee`) and was last released in 2020. AndroidMath is JitPack-only and stale. Tiqian Math is MPL-2.0 (blocked) and in "early development". `io.github.darriousliu:ratex-android` (Compose wrapper) pulls in JetBrains Compose Multiplatform 1.12.2, `material3:1.10.0-alpha05` and compose-resources. **`io.github.erweixin:ratex-android:0.1.15`** (Maven Central, 2026-10-16) is **MIT**, depends only on kotlin-stdlib, androidx.annotation, kotlinx-serialization-json and kotlinx-coroutines, and claims >99.5 % KaTeX syntax coverage. | POMs/AARs from repo1.maven.org; licensee allowlist `app/build.gradle.kts:336-353` (`allow("MIT")`, `allowUrl(".../licenses/MIT")`) |
| RaTeX AAR contents: `classes.jar` 104 KB; 19 KaTeX TTFs in `assets/fonts/` (~0.6 MB); `libratex_ffi.so` for **arm64-v8a 3.2 MB, armeabi-v7a 2.3 MB and x86_64 3.6 MB. There is no x86 build**, though the app ships an `x86` split. | `unzip -l`; `app/build.gradle.kts:54` |
| RaTeX API: `RaTeXEngine.parseBlocking(latex, displayMode, @ColorInt color): DisplayList` throws `RaTeXException`. Its `init` calls `System.loadLibrary("ratex_ffi")`, so a missing `.so` raises a `LinkageError`. `DisplayList(width, height, depth, items)` is in em, and item colours are baked in at parse time. `RaTeXRenderer(dl, fontSizePx, typefaceLoader).draw(Canvas)` adds a 1 px `glyphVerticalBleedPx` offset. `RaTeXFontLoader.ensureLoaded(context)` loads fonts from `assets/fonts`. | `ratex-android-0.1.15-sources.jar` |
| The local Rust SDK AAR `libraries/rustsdk/matrix-rust-sdk.aar` is **missing in this worktree**, and `:libraries:matrix:impl` refuses to build without it. The main checkout has one. | `libraries/matrix/impl/build.gradle.kts:26` |
| A dedicated e2e sandbox exists: Synapse `127.0.0.1:18028`, AVD `interactive_ui_a1` (API 33 x86_64, `emulator-5584`), accounts Alice and uibot, and an encrypted Node fixture bot with a bearer-token control API. | `tools/interactive-ui/{sandbox,fixture-bot}/`, `docs/features/interactive-ui-verification.md` |

## Rulings

- **R1 Scope: receive and render only** (user, 2026-11-05). Composing or sending maths from Android is out of scope
  and will be a follow-up plan.
- **R2 Two sources of maths** (user, 2026-11-05: "also detect delimiters"):
  - **(a) Spec markup.** `span|div[data-mx-maths]` in an HTML `formatted_body`.
  - **(b) Delimiter detection** for messages without spec markup. `$…$`, `\(…\)`, `$$…$$` and `\[…\]` are found
    in the raw `body` using Element Web's approved patterns, so both clients agree on what counts as maths. `\$` is a
    literal dollar, code is never maths, and a closing `$` followed by a digit doesn't count.

  A message that contains any `data-mx-maths` uses (a) only, because its sender is maths-aware.
- **R3 Both renderers, with L1 fixed** (user, 2026-11-05).
  - In the legacy renderer, a formula wider than the available text width is **scaled down to fit**, so it is not
    clipped. To stay readable the scale never goes below 0.4. A formula wider than 2.5× the text width is drawn at 0.4
    and its right part is clipped, which should be very rare.
  - The Compose renderer scrolls wide display maths horizontally.
- **R4 Library: RaTeX** `io.github.erweixin:ratex-android:0.1.15` (user, 2026-11-05). It is isolated behind our own
  `MathsLayoutEngine` interface.
- **R5 Always on, no flag** (user, 2026-11-05). There is no `FeatureFlags` entry, no Labs toggle and no setting.
  Spec maths and delimiter detection are both active on every install. With no kill switch, R6 and R7 carry the
  safety burden.
- **R6 Fail safe.** Invalid or over-limit TeX, an engine missing on the device (x86) or any error falls back:
  - for (a), to the sender's fallback (`<code>TEX</code>`);
  - for (b), to the original text.

  Maths never breaks a message.
- **R7 No cost for ordinary messages.** Path (a) only runs when the raw `formatted_body` contains `data-mx-maths`.
  Path (b) only runs when `body` contains `$`, `\(` or `\[`.
- **R8 Stored model unchanged.** Maths lives only in a separate render-only `Document`. `htmlDocument`, `htmlBody`,
  `plainText` and `body` keep their current values, so edit, reply, copy and accessibility are unchanged.

## 1. Goal and acceptance criteria

### Goal

Maths from other Matrix clients and bots is shown typeset in the Element X Android timeline. This covers Element
Web's `data-mx-maths` markup and plain `$…$` / `\(…\)` style LaTeX. It needs no setup, and ordinary messages are
unaffected.

### Acceptance criteria

1. **Always on.** A fresh install renders maths with no setup. No `FeatureFlags` entry, Labs item or setting
   mentions maths; a grep of `FeatureFlags.kt` for `atex` returns nothing.
2. **Spec inline.** `Euler <span data-mx-maths="e^{i\pi}+1=0"><code>e^{i\pi}+1=0</code></span> holds` shows typeset
   maths in place of the code fallback, on the text baseline, with the surrounding text on the same line, in the
   theme's primary text colour. This holds for both renderers.
3. **Spec display.** `<div data-mx-maths="\int_0^1 x\,dx"><code>…</code></div>` renders as its own block.
4. **Wide maths (L1 fixed).**
   - **Legacy:** a formula whose natural width exceeds the text width is drawn at scale `max(0.4, available /
     natural)` and is not clipped when the scale is at least 0.4.
   - **Compose:** a display formula wider than the bubble scrolls horizontally, and its right end is reachable.
5. **Inside structure.** Maths inside `p`, `li`, `blockquote` and `b`/`i`/`a` renders. A maths `div` whose parent is
   not `body`, `blockquote` or `li` renders inline. Maths inside `pre` or `code` is never rendered.
6. **Detection.** With no `data-mx-maths` anywhere in `formatted_body`, these message bodies give these results
   (unit-tested; "→ maths" lists the TeX values rendered):

   | `body` (raw) | `formatted_body` | → maths |
   | --- | --- | --- |
   | `Area $\pi r^2$` | none | `\pi r^2` (inline) |
   | `Identity ($a+b$) holds` | none | `a+b` |
   | `Energy $E=mc^2$ and \(a\,b\)` + blank line + `\[\frac{1}{2}\]` | `<p>Energy $E=mc^2$ and (a,b)</p><p>[\frac{1}{2}]</p>` (CommonMark output) | `E=mc^2`, `a\,b` (inline), `\frac{1}{2}` (display block) |
   | `$$\sum_k k$$` on its own line | none | `\sum_k k` (display) |
   | `Tickets are $5/$10`, `$5-$10`, `$3,$4`, `I paid $5 and $10` | any | none |
   | `\$x\$` | `$x$` | none |
   | `` Use `$x$` `` | `Use <code>$x$</code>` | none |
   | `see [link $x$](https://e.org)` | `<a href="…">link $x$</a>` | none (never inside links) |

7. **Fallback.** Each of these shows the fallback (R6), with the rest of the message rendered, and never crashes:
   - invalid TeX (`\frac{`);
   - empty or blank TeX;
   - more than 2,000 chars;
   - a macro-definition command (`\def`, `\gdef`, `\edef`, `\xdef`, `\let`, `\newcommand`, `\renewcommand`,
     `\providecommand`, `\futurelet`);
   - a layout over 500 em wide or 100 em tall, or with more than 20,000 items;
   - the 51st and later maths element in one message;
   - an engine that is unavailable (no `.so` for the ABI, or a JVM unit test).

   A spec element with no fallback content gets `<code>TEX</code>` synthesised.
8. **Ordinary messages unchanged.** For a message whose `formatted_body` has no `data-mx-maths` and whose `body` has
   none of `$`, `\(`, `\[`, the `TimelineItem*Content` equals today's, and neither the detector nor the layout engine
   is called. This is unit-tested.
9. **Unchanged content model** (R8). For maths messages, `htmlDocument`, `htmlBody`, `plainText` and `body` equal
   their values with the feature disabled.
10. **Coverage.** Text, notice and emote bodies, and image, video, audio, voice and file captions from
    `TimelineItemContentMessageFactory`, including pinned-message lists. The media viewer's full-screen caption is
    out of scope.
11. **Theme.** Uncoloured maths uses the current text colour in light and dark. `\color{red}{x}` keeps red.
12. **Privacy.** Logs never contain TeX or message content; only lengths, counts and exception class names.
13. **Licensing.** `./gradlew :app:licenseeAndroidGplayDebug` passes without changing the allowlist.

### Known limitations (accepted unless the user objects)

- **L2** 32-bit x86 devices show the fallback, because RaTeX has no x86 `.so`.
- **L3** Editing a received maths message on Android re-sends it via Rust Markdown, so `data-mx-maths` is lost (R1).
- **L4** Selecting or copying text across a typeset formula in the legacy renderer yields U+FFFC. "Copy text" uses
  `body`, which keeps the TeX.
- **L5** Detection on HTML maps `body` matches into the HTML text. A match the sender's Markdown restructured is not
  rendered and stays as today's text. Examples: `*` emphasis inside `$a*b*c$`, or a hard line break inside inline
  maths. Detection never invents maths that is absent from `body`.
- **L6** In HTML, `$$…$$` / `\[…\]` render as a display block only when they are the whole content of a paragraph or
  of a top-level, quote or list-item text run. Otherwise they render inline.

## 2. Design

### 2.1 Module boundaries and data flow

```
                     ┌─ toHtmlDocument(keepMaths=false) ─▶ htmlDocument (stored; unchanged, R8)
 formatted_body ─────┤
                     └─ contains "data-mx-maths"  ── (a) toHtmlDocument(keepMaths=true) ─────────────────┐
 body ─── no data-mx-maths && body has $ \( \[                                                             │
          ── (b) MathsDetector.detect(body, htmlDocument)  → clone with span|div[data-mx-maths] inserted ─┤
                                                             (or a new Document built from body)          │
                                                                                          renderDocument ◀┘
             ┌──────────────────────────────────────────────┴─────────────────────────────┐
   legacy (default)                                                         Compose (NewTimelineEventRenderer)
   MathsSpansConverter.convert(renderDocument)                              DefaultHtmlMessageParser.parse(renderDocument)
     clone; maths el → U+FFFC marker or fallback                              span → placeholder "maths_N" + MathsInlineContent
     HtmlConverter.fromDocumentToSpans(clone)                                 div  → MathsBlockNode (horizontal scroll)
     U+FFFC → MathsSpan (ReplacementSpan)
   TimelineItemTextView/CaptionView: withMathsMaxWidth(maxWidthPx) scales to fit
             └──────── both call MathsLayoutEngine.layout(tex, displayMode): computation dispatcher, LRU, never throws ──┘
```

| Module | Change | Owns |
| --- | --- | --- |
| `:libraries:maths:api` (new, `io.element.android-library`) | new | `MathsLayoutEngine`, `MathsLayout`, `MathsDetector` interfaces, `MathsSpan`, `withMathsMaxWidth`, and DOM helpers and constants. `api(libs.jsoup)`. No RaTeX types. |
| `:libraries:maths:impl` (new) | new | `RatexMathsLayoutEngine` and its limits; `DefaultMathsDetector`, the delimiter patterns and DOM insertion. The only module depending on `libs.ratex.android`. Added to `allLibrariesImpl()`. |
| `:libraries:maths:test` (new) | new | `FakeMathsLayout`, `FakeMathsLayoutEngine`, `FakeMathsDetector`. |
| `:libraries:matrixui` | changed | `toHtmlDocument(…, keepMaths)`. |
| `:features:messages:impl` | changed | `MathsSpansConverter`, factory wiring, and width-to-fit in `TimelineItemTextView` and `CaptionView`. |
| `:libraries:htmlrenderer:api`/`impl` | changed | `MathsBlockNode`, `MathsInlineContent`, parser and Compose rendering. |

### 2.2 Dependency

`gradle/libs.versions.toml`:

```toml
[versions]
ratex = "0.1.15"
[libraries]
ratex_android = { module = "io.github.erweixin:ratex-android", version.ref = "ratex" }
```

It resolves from `mavenCentral()`. Gradle upgrades its transitive serialization and coroutines to the app's 1.11.0.

### 2.3 `:libraries:maths:api` (exact signatures)

Package `io.element.android.libraries.maths.api`.

```kotlin
/** Lays out TeX maths for drawing. Implementations are thread-safe and cache results. */
interface MathsLayoutEngine {
    /**
     * Lays out [tex] (without delimiters) in display (block) or inline (text) style.
     * Blocking and CPU-bound: call it off the main thread.
     * @return the layout, or null when the maths cannot be rendered (invalid or over-limit TeX, engine unavailable,
     * unexpected error). Never throws.
     */
    fun layout(tex: String, displayMode: Boolean): MathsLayout?
}

/**
 * A laid-out formula. Dimensions are in em: multiply by the font size in px. The baseline is [heightEm] below the top
 * of the bounding box, which is [widthEm] wide and [heightEm] + [depthEm] tall.
 */
interface MathsLayout {
    val widthEm: Float
    val heightEm: Float
    val depthEm: Float

    /** Draws with the box's top-left at the canvas origin. Parts without an explicit TeX colour use [color]. Never throws. */
    fun draw(canvas: Canvas, fontSizePx: Float, @ColorInt color: Int)
}

/** Finds LaTeX delimiters in message text (R2 b). */
interface MathsDetector {
    /**
     * Finds maths delimiters in the raw [body] (Markdown or plain text) and returns a render-only [Document] with
     * `span|div[data-mx-maths]` elements (each with a `<code>TEX</code>` fallback) inserted:
     * - into a clone of [document] (the sanitized formatted body) when it is non-null, mapping each match into the HTML
     *   text (see §2.6);
     * - otherwise into a new Document built from [body] (newlines become `<br>`).
     * Returns null when nothing was inserted. Never mutates [document]. Never throws.
     */
    fun detect(body: String, document: Document?): Document?
}
```

`MathsSpan.kt`, the legacy `TextView` path:

```kotlin
/**
 * Draws [layout] in place of the single character it spans (U+FFFC), on the text baseline, at the paint's text size
 * and colour, scaled down to fit [maxWidthPx] (never below [MIN_FIT_SCALE]). Line height grows to fit the formula.
 */
class MathsSpan(
    val tex: String,
    val layout: MathsLayout,
    val displayMode: Boolean,
    val maxWidthPx: Int = Int.MAX_VALUE,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint)

    companion object {
        const val MIN_FIT_SCALE = 0.4f
    }
}

/**
 * Returns [this] with every [MathsSpan] replaced by a copy limited to [maxWidthPx], or [this] itself when it has none.
 * Pure; used by the timeline text views with their measured max width.
 */
fun CharSequence.withMathsMaxWidth(maxWidthPx: Int): CharSequence

/** True when [this] is a [Spanned] containing at least one [MathsSpan]. */
fun CharSequence.containsMathsSpans(): Boolean
```

How `MathsSpan` sizes and draws:

- `s = paint.textSize`, `natural = widthEm*s`, `k = if (natural > maxWidthPx) max(MIN_FIT_SCALE, maxWidthPx/natural)
  else 1f`, and `fs = s*k`.
- `getSize`: when `fm != null`, call `paint.getFontMetricsInt(fm)`, then only expand it:
  - `fm.ascent = min(fm.ascent, -ceil(heightEm*fs))`, `fm.top = min(fm.top, fm.ascent)`;
  - `fm.descent = max(fm.descent, ceil(depthEm*fs))`, `fm.bottom = max(fm.bottom, fm.descent)`.

  Return `ceil(widthEm*fs)`.
- `draw`: `canvas.withTranslation(x, y - heightEm*fs) { layout.draw(this, fs, paint.color) }`.

`MathsElements.kt`:

```kotlin
/** Matrix spec attribute carrying the TeX source on `span` (inline) and `div` (display) elements. */
const val MATHS_ATTRIBUTE = "data-mx-maths"

/** Maximum number of maths elements rendered per message; later ones fall back. */
const val MAX_MATHS_ELEMENTS_PER_MESSAGE = 50

fun Element.isMathsElement(): Boolean          // span|div with MATHS_ATTRIBUTE
fun Element.isDisplayMaths(): Boolean          // a maths div
fun Element.mathsTex(): String                 // attribute value (entities decoded by jsoup)

/** Replaces a maths element with its fallback children: unwraps a `span`, renames a `div` to `p`. */
fun Element.replaceMathsWithFallback()

/** Creates `<span|div data-mx-maths="tex"><code>tex</code></span|div>` owned by [document]. */
fun createMathsElement(document: Document, tex: String, displayMode: Boolean): Element
```

### 2.4 `:libraries:maths:impl` layout engine

```kotlin
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RatexMathsLayoutEngine(
    @ApplicationContext private val context: Context,
) : MathsLayoutEngine {
    override fun layout(tex: String, displayMode: Boolean): MathsLayout?
}

internal object MathsPrecheck {
    const val MAX_TEX_LENGTH = 2_000
    val FORBIDDEN_COMMANDS: Set<String> // def gdef edef xdef let newcommand renewcommand providecommand futurelet
    /** Non-blank, at most MAX_TEX_LENGTH, and no `\name` from FORBIDDEN_COMMANDS that is not followed by a letter. */
    fun isAllowed(tex: String): Boolean
}

internal class RatexMathsLayout(private val displayList: DisplayList) : MathsLayout

/** [this] with every item whose colour matches [DEFAULT_COLOR_SENTINEL] (per channel, ±0.5/255) recoloured to [color]. */
internal fun DisplayList.withDefaultColor(@ColorInt color: Int): DisplayList

internal const val DEFAULT_COLOR_SENTINEL: Int = 0xFF1F74C9.toInt()
internal const val MAX_WIDTH_EM = 500f
internal const val MAX_HEIGHT_EM = 100f
internal const val MAX_DISPLAY_ITEMS = 20_000
internal const val MAX_CACHED_LAYOUTS = 256
```

`layout` works in this order:

1. Return null when `MathsPrecheck.isAllowed` fails, or when the engine is already marked unavailable (`@Volatile`).
2. Look up the `LruCache<(tex, displayMode), Any>` (size 256). It caches failures as a private `Failed` marker too.
3. In a `try` block: `RaTeXFontLoader.ensureLoaded(context)`, then
   `RaTeXEngine.parseBlocking(tex, displayMode, DEFAULT_COLOR_SENTINEL)`. Reject the result if it is over the size or
   item limits; otherwise return `RatexMathsLayout(dl)`.
4. Handle failures:
   - `RaTeXException`: return null and log `Timber.d("Maths layout rejected (length=%d)")`.
   - `LinkageError`: mark the engine unavailable, log `Timber.w` once with the class name, and return null.
   - Any other `Exception`: return null and log `Timber.w` with the class name only.

`RatexMathsLayout.draw` works like this:

- It keeps a one-entry `@Volatile` memo of `color → withDefaultColor(color)`, then builds
  `RaTeXRenderer(dl, fontSizePx) { RaTeXFontLoader.getTypeface(it) }`.
- It draws inside `canvas.withTranslation(0f, -renderer.glyphVerticalBleedPx) { renderer.draw(this) }`, so the
  baseline is exactly `heightEm*fontSizePx` below the origin.
- It catches `Exception` and logs once.

### 2.5 `:libraries:maths:test`

```kotlin
class FakeMathsLayout(
    override val widthEm: Float = 2f,
    override val heightEm: Float = 0.75f,
    override val depthEm: Float = 0.25f,
) : MathsLayout {
    val drawCalls: List<Pair<Float, Int>> // (fontSizePx, color) per draw
    override fun draw(canvas: Canvas, fontSizePx: Float, color: Int) // fills its bounds with [color]
}

class FakeMathsLayoutEngine(
    private val layoutResult: (tex: String, displayMode: Boolean) -> MathsLayout? = { _, _ -> FakeMathsLayout() },
) : MathsLayoutEngine {
    val calls: List<Pair<String, Boolean>>
    override fun layout(tex: String, displayMode: Boolean): MathsLayout?
}

class FakeMathsDetector(
    private val detectResult: (body: String, document: Document?) -> Document? = { _, _ -> null },
) : MathsDetector {
    val calls: List<String>
    override fun detect(body: String, document: Document?): Document?
}
```

### 2.6 `:libraries:maths:impl` delimiter detection

```kotlin
@ContributesBinding(AppScope::class)
class DefaultMathsDetector : MathsDetector {
    override fun detect(body: String, document: Document?): Document?
}

/** A maths span found in raw source. [range] covers the delimiters and the TeX, but not the leading context char. */
internal data class MathsMatch(val range: IntRange, val tex: String, val displayMode: Boolean)

internal object MathsDelimiters {
    /**
     * Finds maths in [source] in document order. Backtick code spans and ``` fences are masked first (replaced by
     * U+0000 of the same length). Patterns run in this order, each on the source with earlier matches masked:
     * tex display, tex inline, latex display, latex inline. Every regex uses MULTILINE and DOT_MATCHES_ALL.
     */
    fun find(source: String): List<MathsMatch>

    // Element Web's approved patterns (Web plan §maths.ts). Group 1 = context kept before the maths, group 2 = TeX.
    const val TEX_DISPLAY = """(^)\$\$(([^$]|\\\$)+?)\$\$$"""
    const val TEX_INLINE = """(^|[\s.,!?:;(\[{"'])(?!\\)\$(?!\s)(([^$\n]|\\\$)*([^\\\s\$]|\\\$)(?:\\\$)?)\$(?!\d)"""
    const val LATEX_DISPLAY = """(^)\\\[(?!\\\])(.*?)\\\]$"""
    const val LATEX_INLINE = """(^|[^\\])\\\((?!\\\))(.*?)\\\)"""
}

/** CommonMark backslash-escape removal: `\` followed by ASCII punctuation becomes that punctuation. */
internal fun unescapeMarkdown(text: String): String
```

`detect(body, document)`:

1. Quick reject: return null if `body` contains none of `$`, `\(`, `\[`. Then `matches = MathsDelimiters.find(body)`
   (at most `MAX_MATHS_ELEMENTS_PER_MESSAGE`). Return null when it is empty.
2. **No HTML** (`document == null`):
   - Build `Document.createShell("")`. Walk `body`, appending a `TextNode` for each text gap (each `\n` becomes
     `<br>`) and `createMathsElement(...)` for each match.
   - A display match becomes a body-level `div`. The newline right before or after a display `div` is dropped,
     because the `div` already breaks the line.
   - Return the document.
3. **HTML** (`document != null`):
   - Take `copy = document.clone()`.
   - Set up a cursor over its text nodes in document order, skipping descendants of `pre`, `code`, `a`, `mx-reply`
     and maths elements.
   - For each match, in order:
     1. Build the `needle`: `unescapeMarkdown(body.substring(match.range))`, turned into a regex with
        `Regex.escape` on each non-whitespace run and `\s+` between runs.
     2. Search from the cursor within single text nodes. If there is no hit, skip the match (L5).
     3. On a hit, split the text node around it.
     4. For a display match whose hit is the whole trimmed text of a `p` element (or of a text run whose parent is
        `body`, `blockquote` or `li`), replace that `p` (or text node) with a maths `div` (L6). Otherwise insert a
        maths `span` with the TeX from `body` (`match.tex`).
     5. Advance the cursor past the insertion.
   - Return `copy` if at least one element was inserted, else null.
4. Catch any `Exception`, return null, and log `Timber.w` with the class name only.

### 2.7 `:libraries:matrixui` sanitizer (spec path a)

```kotlin
fun FormattedBody.toHtmlDocument(
    permalinkParser: PermalinkParser,
    prefix: String? = null,
    keepMaths: Boolean = false,
): Document?
```

- `keepMaths = false` is exactly the current code, so every existing caller is untouched.
- `keepMaths = true` cleans with `Safelist(safeList).addTags("span", "div").addAttributes("span", MATHS_ATTRIBUTE)
  .addAttributes("div", MATHS_ATTRIBUTE)`. It then runs `normaliseMaths(dom)` before `fixMentions`:
  1. Unwrap every `span`/`div` without a non-blank `data-mx-maths`.
  2. Unwrap maths inside `pre`, `code` or another maths element.
  3. Rename a maths `div` whose parent is not `body`, `blockquote` or `li` to `span`.
  4. Give a maths element with blank text `<code>TEX</code>`.

### 2.8 No feature flag

Per R5 there is no flag. `FeatureFlags.kt`, the Labs screen and the developer options are untouched. The only gates
are the cheap content checks in §2.9 (R7).

### 2.9 `:features:messages:impl` (legacy path, wiring, fit-to-width)

New `timeline/factories/event/MathsSpansConverter.kt`:

```kotlin
@Inject
class MathsSpansConverter(
    private val mathsLayoutEngine: MathsLayoutEngine,
) {
    /**
     * Converts [document] to spans with [toSpans], replacing each maths element that lays out with a [MathsSpan] and
     * each one that does not with its fallback. [document] is never mutated. Never throws for maths reasons.
     */
    fun convert(document: Document, toSpans: (Document) -> CharSequence): CharSequence
}
```

How `convert` works:

1. Take `copy = document.clone()` and select its maths elements in document order. If there are none, return
   `toSpans(copy)`.
2. If `copy.text()` already contains U+FFFC, replace every maths element with its fallback and convert.
3. Otherwise, for index `i`:
   - when `i >= 50` or `layout(...) == null`, call `replaceMathsWithFallback()`;
   - else replace the element with `TextNode("￼")` (for a span) or `<p>￼</p>` (for a div), and record
     `MathsSpan(tex, layout, display)`.
4. Run `spanned = toSpans(copy)`. If the number of U+FFFC characters differs from the number of recorded spans,
   log `Timber.w("Maths marker mismatch (expected=%d, actual=%d)")` and return a full-fallback conversion of a fresh
   clone.
5. Otherwise return `SpannableStringBuilder(spanned)` with each span set on `[i, i+1)`, `SPAN_EXCLUSIVE_EXCLUSIVE`.

`TimelineItemContentMessageFactory`:

- The constructor gains `mathsSpansConverter: MathsSpansConverter` and `mathsDetector: MathsDetector`.
- A new private helper:

  ```kotlin
  /** Render-only DOM (R8): stored DOM unless maths applies; see §2.1. [plainBody] is the raw body used for detection. */
  private fun renderDocument(
      formatted: FormattedBody?,
      stored: Document?,
      plainBody: String?,
      prefix: String? = null,
  ): Document? {
      if (stored != null && formatted?.body?.contains(MATHS_ATTRIBUTE) == true) {
          return formatted.toHtmlDocument(permalinkParser = permalinkParser, prefix = prefix, keepMaths = true) ?: stored
      }
      if (plainBody == null || MATHS_DELIMITER_HINTS.none(plainBody::contains)) return stored
      return mathsDetector.detect(plainBody, stored) ?: stored
  }

  private val MATHS_DELIMITER_HINTS = listOf("$", "\\(", "\\[")
  ```

  A test asserts that the detector is not called for a message with no `data-mx-maths` and no `$`, `\(` or `\[`.
- `parseHtml(document)` becomes `mathsSpansConverter.convert(document) { htmlConverterProvider.provide()
  .fromDocumentToSpans(it) }`, followed by the existing `pillify` and `safeLinkify`.
- Text, Notice and Emote:
  - `val render = renderDocument(messageType.formatted, htmlDocument, plainBody = <body as rendered>)`. For Emote the
    plain body is the prefixed `emoteBody`, and the prefix argument is `"* $name"`.
  - `formattedBody = render?.let(::parseHtml) ?: <today's plain path>`.
  - `messageTree = …render?.let(htmlMessageParser::parse)`.
  - `htmlDocument` stays as stored.
- Each caption branch that calls `parseHtml` today (Image, Video, Audio, Voice, File and any other `formattedCaption`
  branch) uses `renderDocument(messageType.formattedCaption, dom, plainBody = messageType.caption)`.

Fit to width, in `TimelineItemTextView` (legacy branch) and `CaptionView`:

- Compute `val text = …` as today, then `val hasMaths = remember(text) { text.containsMathsSpans() }`.
- When `hasMaths`, wrap the `EditorStyledText` in `BoxWithConstraints(modifier)` and pass
  `remember(text, constraints.maxWidth) { text.withMathsMaxWidth(constraints.maxWidth) }`.
- Otherwise keep the current `Box`, with no new cost.
- The existing `semantics { contentDescription = plainText }` is unchanged.

Test fixtures (`TimelineItemsFactoryFixtures.kt` and the factory test helper) pass
`MathsSpansConverter(FakeMathsLayoutEngine())` and `FakeMathsDetector()`.

### 2.10 `:libraries:htmlrenderer` Compose path

API (`MessageNode.kt`):

```kotlin
/** A display-style (block) formula from `div[data-mx-maths]` that laid out successfully. */
@Immutable
data class MathsBlockNode(val tex: String, val layout: MathsLayout) : BlockNode

/** Inline formula drawn at an inline-content placeholder of a [ParagraphNode]. */
@Immutable
data class MathsInlineContent(val tex: String, val layout: MathsLayout)

data class ParagraphNode(
    val text: AnnotatedString,
    val inlineContent: ImmutableMap<String, MentionNodeContent> = persistentMapOf(),
    val inlineMaths: ImmutableMap<String, MathsInlineContent> = persistentMapOf(),
) : BlockNode
```

`lastBlockNode()` gains `is MathsBlockNode -> this`, so the existing `node !is ParagraphNode` rule reports
`NotOverlapping`.

Parser (`DefaultHtmlMessageParser`): the constructor gains `mathsLayoutEngine: MathsLayoutEngine`. There is a
per-`parse` counter capped at 50.

- `isBlockLevel()` is also true for a maths `div`.
- In `parseBlock`, a maths `div` with a layout becomes `MathsBlockNode`. Otherwise it becomes
  `buildParagraph(childNodes)`, the fallback.
- In `appendElement`, a maths `span` with a layout gets `appendInlineContent("maths_$n", alternateText = tex)` and an
  `inlineMaths` entry. Otherwise its children are appended, so the `<code>` fallback shows as inline code.
- `build()` treats a paragraph with `inlineMaths` as non-empty.

UI (`HtmlMessageContent.kt`):

- `BlockNodeView`: `is MathsBlockNode -> MathsBlockView(node)`. This is a `Box(Modifier.horizontalScroll(
  rememberScrollState()))` around a `Canvas` of size `widthEm*fs × (heightEm+depthEm)*fs`, with `fs` taken from
  `fontBodyMdRegular`. The canvas draws with `drawIntoCanvas { node.layout.draw(it.nativeCanvas, fsPx,
  textPrimary.toArgb()) }`.
- `ParagraphView` merges the mention inline content with a new `rememberMathsInlineContent(node.inlineMaths, style)`.
- Baseline alignment with `PlaceholderVerticalAlign.TextCenter`:
  - Measure `"x"` with `style.copy(lineHeight = TextUnit.Unspecified)` to get `c = (firstBaseline - height/2) /
    fontSizePx`, the text centre in em above the baseline.
  - For each formula, `H = max(heightEm - c, depthEm + c)`.
  - Use `Placeholder(widthEm.em, (2*H).em, TextCenter)`.
  - Draw the content at `y = (c + H - heightEm) * fontSizePx`.
- Inline formulas wider than the paragraph are scaled to fit, as in the legacy span, with the same 0.4 floor.
- Previews: `HtmlMessageContentPreview` gains inline, display and wide maths states using a private
  `PreviewMathsLayout`, which draws rect and line primitives because Paparazzi cannot load the `.so`.

### 2.11 Error handling and logging

Every failure resolves to the fallback (R6), at four layers:

- `MathsLayoutEngine` and `MathsDetector` return null;
- `MathsSpansConverter` falls back on a marker mismatch;
- `MathsLayout.draw` catches exceptions.

There is no error UI. Use Timber only, and never log TeX, `body`, `formatted_body` or rendered text; log only
lengths, counts and exception class names. A missing native library is logged once per process.

### 2.12 Conventions

- Headers: `Copyright (c) 2026 Element Creations Ltd.` with SPDX `AGPL-3.0-only OR LicenseRef-Element-Commercial`.
- Run `./gradlew ktlintFormat`; hard wrap at 160.
- Metro DI: `@ContributesBinding(AppScope::class)`; `@SingleIn(AppScope::class)` for the layout engine and its cache.
- There are no user-facing strings, so `temporary.xml` is untouched.
- Previews use `@PreviewsDayNight`/`ElementPreview`. Screenshots are recorded only by CI (the `Record-Screenshots`
  label on the PRs for T3 and T4).
- Delivery is about 1,050 production additions, so one PR per task, each ≤ 500 production lines:

  | Task | Production lines (approx.) |
  | --- | --- |
  | T1 | 330 |
  | T2 | 220 |
  | T3 | 230 |
  | T4 | 230 |

  Label `pr-feature`. Sentence-case titles.

## 3. Tasks

Prerequisite for every task (not a task): copy the Rust SDK AAR and record its hash. Never edit the main checkout.

```sh
cp /home/sinkerine/dev/element-x-android/libraries/rustsdk/matrix-rust-sdk.aar libraries/rustsdk/
sha256sum libraries/rustsdk/matrix-rust-sdk.aar
```

Run Gradle inside `nix develop` (JDK 21, SDK 37).

### Wave 1

**T1: Maths layout library, contracts and spec-markup sanitizer**

- Goal: provide the engine, layout, span (with fit-to-width), detector interface and fake, DOM helpers and the
  `keepMaths` sanitizer (§2.2–2.5, §2.7). Every renderer and the detector build on this contract.
- Owned files:
  - `gradle/libs.versions.toml`
  - `libraries/maths/api/**`
  - `libraries/maths/impl/build.gradle.kts`
  - `libraries/maths/impl/src/main/kotlin/io/element/android/libraries/maths/impl/layout/**`
  - `libraries/maths/impl/src/test/kotlin/io/element/android/libraries/maths/impl/layout/**`
  - `libraries/maths/test/**`
  - `plugins/src/main/kotlin/extension/DependencyHandleScope.kt`
  - `libraries/matrixui/build.gradle.kts`
  - `libraries/matrixui/src/main/kotlin/io/element/android/libraries/matrix/ui/messages/ToHtmlDocument.kt`
  - `libraries/matrixui/src/test/kotlin/io/element/android/libraries/matrix/ui/messages/ToHtmlDocumentTest.kt`
- Consumes: `libs.jsoup`, `@ApplicationContext`, the RaTeX API.
- Produces:
  - `MathsLayoutEngine`, `MathsLayout`, `MathsDetector`;
  - `MathsSpan(…, maxWidthPx)`, `withMathsMaxWidth`, `containsMathsSpans`;
  - `MATHS_ATTRIBUTE`, `MAX_MATHS_ELEMENTS_PER_MESSAGE`, and the `isMathsElement`, `isDisplayMaths`, `mathsTex`,
    `replaceMathsWithFallback` and `createMathsElement` helpers;
  - the `RatexMathsLayoutEngine` binding;
  - `FakeMathsLayout`, `FakeMathsLayoutEngine`, `FakeMathsDetector`;
  - `toHtmlDocument(…, keepMaths)`.
- Dependencies: none.
- Tests:
  - `MathsPrecheckTest`: blank; 2,000 vs 2,001 chars; each forbidden command; `\definecolor` and `\left` allowed.
  - `DisplayListRecolorTest`: sentinel items recoloured, `\color` items kept, every item type.
  - `RatexMathsLayoutEngineTest` (Robolectric, no `.so` on the JVM): returns null, doesn't throw, and doesn't retry
    loading.
  - `MathsSpanTest` (Robolectric):
    - natural size;
    - metrics only expanded;
    - draw translation, size and colour;
    - fit scale `max(0.4, max/natural)`;
    - `withMathsMaxWidth` returns the same instance when there are no maths spans, and replaces every span otherwise.
  - `MathsElementsTest`.
  - `ToHtmlDocumentTest`:
    - `keepMaths=false` output identical to today;
    - span/div maths kept;
    - non-maths spans unwrapped;
    - nested maths, maths in `pre`/`code`, and display maths in `b` normalised;
    - blank fallback synthesised.
- Command: `./gradlew :libraries:maths:api:testDebugUnitTest :libraries:maths:impl:testDebugUnitTest :libraries:matrixui:testDebugUnitTest`

### Wave 2 (T2, T3 and T4 are independent, with disjoint files; all consume only T1)

**T2: Delimiter detection**

- Goal: implement `MathsDetector` (§2.6), so a message without spec markup gets a render-only DOM with maths
  elements for the `$…$`, `$$…$$`, `\(…\)` and `\[…\]` found in its raw body.
- Owned files:
  - `libraries/maths/impl/src/main/kotlin/io/element/android/libraries/maths/impl/detection/**`
  - `libraries/maths/impl/src/test/kotlin/io/element/android/libraries/maths/impl/detection/**`
- Consumes (T1): `MathsDetector`, `createMathsElement`, `MATHS_ATTRIBUTE`, `MAX_MATHS_ELEMENTS_PER_MESSAGE`.
- Produces: the `DefaultMathsDetector` binding.
- Dependencies: T1.
- Tests:
  - `MathsDelimitersTest`: Web's exact-case table (AC 6 and the Web plan AC 3), code masking, ordering, the cap.
  - `UnescapeMarkdownTest`.
  - `DefaultMathsDetectorTest`:
    - every AC 6 row, including the CommonMark-output case;
    - display-to-`div` only for whole paragraphs (L6);
    - an unmatched needle is skipped;
    - nothing inside `a`, `code` or `pre`;
    - the input `Document` is unchanged;
    - plain-mode `<br>` construction;
    - the quick reject (no `$`, `\(` or `\[` → null without regex work).
- Command: `./gradlew :libraries:maths:impl:testDebugUnitTest --tests '*detection*'`

**T3: Legacy span rendering, factory wiring and fit-to-width**

- Goal: maths renders, always on, in the default (legacy) renderer for bodies and captions (§2.9). Wide formulas are
  scaled to the view width.
- Owned files:
  - `features/messages/impl/build.gradle.kts`
  - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/factories/event/MathsSpansConverter.kt` (new)
  - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/factories/event/TimelineItemContentMessageFactory.kt`
  - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/event/TimelineItemTextView.kt`
  - `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/timeline/components/event/TimelineItemEventContentView.kt` (`CaptionView`)
  - `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/timeline/factories/event/MathsSpansConverterTest.kt` (new)
  - `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/timeline/factories/event/TimelineItemContentMessageFactoryTest.kt`
  - `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/fixtures/TimelineItemsFactoryFixtures.kt`
- Consumes (T1): `MathsLayoutEngine`, `MathsDetector`, `MathsSpan`, `withMathsMaxWidth`, `containsMathsSpans`, the DOM
  helpers, `toHtmlDocument(keepMaths)`, and the fakes.
- Produces: `MathsSpansConverter`, and maths-aware `formattedBody`/`formattedCaption`/`messageTree` input.
- Dependencies: T1.
- Tests:
  - `MathsSpansConverterTest` (Robolectric, stub `toSpans` using jsoup text):
    - one span per laid-out element at its U+FFFC;
    - fallback text when the layout is null;
    - the 51st element falls back;
    - a pre-existing U+FFFC forces fallback;
    - a marker mismatch falls back;
    - the input is not mutated.
  - `TimelineItemContentMessageFactoryTest`:
    - spec maths → a `MathsSpan` in `formattedBody`, with `htmlDocument`, `htmlBody` and `plainText` equal to the
      non-maths parse;
    - detection → the detector is called with `body`, and its document is rendered;
    - spec maths present → the detector is not called;
    - no `data-mx-maths` and no `$`, `\(`, `\[` → neither the detector nor the layout engine is called, and the
      content equals today's (AC 8);
    - notice, emote (prefixed body) and image caption.
- Command: `./gradlew :features:messages:impl:testDebugUnitTest --tests '*MathsSpansConverterTest*' --tests '*TimelineItemContentMessageFactoryTest*'`

**T4: Compose renderer support**

- Goal: maths renders in the Compose message renderer, with inline baseline alignment, fit-to-width inline formulas
  and scrollable display blocks (§2.10).
- Owned files:
  - `libraries/htmlrenderer/api/build.gradle.kts`
  - `libraries/htmlrenderer/api/src/main/kotlin/io/element/android/libraries/htmlrenderer/api/MessageNode.kt`
  - `libraries/htmlrenderer/api/src/main/kotlin/io/element/android/libraries/htmlrenderer/api/ui/HtmlMessageContent.kt`
  - `libraries/htmlrenderer/api/src/main/kotlin/io/element/android/libraries/htmlrenderer/api/ui/HtmlMessageContentPreview.kt`
  - `libraries/htmlrenderer/impl/build.gradle.kts`
  - `libraries/htmlrenderer/impl/src/main/kotlin/io/element/android/libraries/htmlrenderer/impl/DefaultHtmlMessageParser.kt`
  - `libraries/htmlrenderer/impl/src/test/kotlin/io/element/android/libraries/htmlrenderer/impl/DefaultHtmlMessageParserTest.kt`
- Consumes (T1): `MathsLayoutEngine`, `MathsLayout`, the DOM helpers, `FakeMathsLayoutEngine`.
- Produces: `MathsBlockNode`, `MathsInlineContent`, `ParagraphNode.inlineMaths`.
- Dependencies: T1.
- Tests: `DefaultHtmlMessageParserTest`:
  - inline → `maths_0` placeholder with alternate text = TeX, plus an `inlineMaths` entry;
  - display at the top level, in `li` and in `blockquote` → `MathsBlockNode`;
  - null layout → inline code / paragraph fallback;
  - the cap of 50;
  - a paragraph containing only maths is kept;
  - mentions and maths coexist;
  - existing cases unchanged.
- Previews: new maths states (`Record-Screenshots`).
- Command: `./gradlew :libraries:htmlrenderer:impl:testDebugUnitTest :libraries:htmlrenderer:api:compileDebugKotlin`

### Wave 3

**T5: Maths fixtures and the e2e journey**

- Goal: send Web-format and bot-style maths events in the dedicated sandbox. Execute §5 on the emulator for both
  renderers, and record the result in `docs/features/latex-verification.md`.
- Owned files:
  - `tools/interactive-ui/fixture-bot/lib/maths-fixtures.mjs` (new)
  - `tools/interactive-ui/fixture-bot/lib/maths-fixtures.test.mjs` (new)
  - `tools/interactive-ui/fixture-bot/bot.mjs` (adds `send-maths <name>`)
  - `tools/interactive-ui/fixture-bot/package.json` and `package-lock.json` (adds dev dependency `commonmark@0.31.2`)
  - `tools/interactive-ui/fixture-bot/README.md`
  - `docs/features/latex-verification.md` (new)
- Consumes: T2, T3 and T4 behaviour; the sandbox scripts and bot.
- Produces: `ctl send-maths <name>`. It sends `{msgtype:"m.text", body}`, plus `format:"org.matrix.custom.html",
  formatted_body` when the fixture has HTML. Also the verification record.
- Fixtures (Web strings copied from Web's serializer output; bot strings are exact CommonMark output):

  | name | `body` | `formatted_body` |
  | --- | --- | --- |
  | `spec-inline` | `Euler $e^{i\pi}+1=0$ holds` | `Euler <span data-mx-maths="e^{i\pi}+1=0"><code>e^{i\pi}+1=0</code></span> holds` |
  | `spec-display` | `$$\int_0^1 x\,dx = \frac{1}{2}$$` | `<div data-mx-maths="\int_0^1 x\,dx = \frac{1}{2}"><code>\int_0^1 x\,dx = \frac{1}{2}</code></div>` |
  | `spec-wide` | display `\sum_{k=1}^{40} a_k x^k` written out to about 60 em | same pattern as `spec-display` |
  | `spec-mixed` | list, quote and bold items with maths, plus `\color{red}{x}` | `<ul><li>a <span data-mx-maths="a^2"><code>a^2</code></span></li></ul><blockquote><span data-mx-maths="\color{red}{x}"><code>\color{red}{x}</code></span></blockquote><p><b>b <span data-mx-maths="b_1"><code>b_1</code></span></b></p>` |
  | `spec-invalid` | `bad $\frac{$` | `bad <span data-mx-maths="\frac{"><code>\frac{</code></span>` |
  | `spec-macro` | `$\def\a{x}\a$` | `<span data-mx-maths="\def\a{x}\a"><code>\def\a{x}\a</code></span>` |
  | `plain-detect` | `Area $\pi r^2$, root \(\sqrt{2}\)` + newline + `$$\sum_{k=1}^n k = \frac{n(n+1)}{2}$$` | none |
  | `md-detect` | `Energy $E=mc^2$ and \(a\,b\)` + blank line + `\[\frac{1}{2}\]` | `<p>Energy $E=mc^2$ and (a,b)</p>\n<p>[\frac{1}{2}]</p>` |
  | `no-maths` | `Tickets are $5/$10; I paid $5 and $10; \$x\$; use `$x$`` | `<p>Tickets are $5/$10; I paid $5 and $10; $x$; use <code>$x$</code></p>` |
  | `canary` | `$\text{E2ELATEXCANARY7Qx}$` | none |

  `maths-fixtures.test.mjs` checks that each `formatted_body` parses, that spec fixtures' `data-mx-maths` values equal
  their TeX, and that the `md-detect` HTML equals `commonmark` 0.31's output for its body.
- Dependencies: T2, T3, T4.
- Command: `(cd tools/interactive-ui/fixture-bot && npm ci && node --test)`. Completion also requires the executed
  §5 journey recorded in `latex-verification.md`.

## 4. Test and verification (whole feature)

Prerequisites: the AAR copy from §3 and `nix develop`.

```sh
./gradlew :libraries:maths:api:testDebugUnitTest :libraries:maths:impl:testDebugUnitTest \
  :libraries:matrixui:testDebugUnitTest :libraries:htmlrenderer:impl:testDebugUnitTest \
  :features:messages:impl:testDebugUnitTest
./gradlew :tests:konsist:testDebugUnitTest
./gradlew detekt ktlintCheck
./gradlew :app:lintGplayDebug lintDebug --continue
./gradlew :app:licenseeAndroidGplayDebug              # MIT RaTeX passes the allowlist unchanged
./gradlew :app:compileGplayDebugKotlin :app:compileFdroidDebugKotlin
./gradlew :app:assembleGplayDebug
unzip -l app/build/outputs/apk/gplay/debug/app-gplay-x86_64-debug.apk | grep -E 'libratex_ffi.so|assets/fonts/KaTeX_Main-Regular.ttf'
./gradlew :app:testGplayDebugUnitTest testDebugUnitTest -x :tests:konsist:testDebugUnitTest   # full regression
(cd tools/interactive-ui/fixture-bot && npm ci && node --test)
```

Record the APK size delta per ABI against the base commit. Screenshot tests run in CI only.

## 5. E2E journey

Sandbox (dedicated, reused; never personal rooms):

1. Start Synapse with `tools/interactive-ui/sandbox/synapse-up.sh`.
2. Start the emulator with `avd-up.sh` (`interactive_ui_a1`, `emulator-5584`, API 33 x86_64). Then run
   `adb -s emulator-5584 reverse tcp:18028 tcp:18028`.
3. Start the bot with `bot.mjs serve --evidence build/e2e/latex/<UTC>`, then run `ctl setup-room` (a new encrypted
   room with Alice invited).
4. Build `:app:assembleGplayDebug`. Install `app-gplay-x86_64-debug.apk` once with `adb -s emulator-5584 install -r
   --no-incremental`. Confirm `firstInstallTime < lastUpdateTime`.
5. Alice's existing session is preserved. Never clear app data.

Use Orca or `adb -s emulator-5584` for screenshots and accessibility dumps. Evidence goes in `build/e2e/latex/<UTC>/`.

| # | Step | Expected result |
| --- | --- | --- |
| 1 | Settings → Labs, then Developer options, as Alice. | No maths-related toggle exists anywhere. Developer options "New timeline event renderer" (pre-existing) is off. |
| 2 | Open the fixture room. Run `ctl send-maths spec-inline`. | "Euler", a typeset e^{iπ}+1=0 (raised superscript, π glyph, no monospace code box) and "holds" are on one line, with the formula baseline on the text baseline. The accessibility content description contains `e^{i\pi}+1=0`. |
| 3 | `ctl send-maths spec-display` | The integral with ½ appears as its own line and block, with no code box. The timestamp does not overlap it. |
| 4 | `ctl send-maths spec-wide` | The whole formula is visible inside the bubble, scaled down and not clipped (L1 fix). Screenshot compared with the bubble bounds from the accessibility tree. |
| 5 | `ctl send-maths spec-mixed` | Maths in the bullet, quote and bold all render. `x` is red; the rest is primary colour. |
| 6 | `ctl send-maths plain-detect` (no HTML) | π r² and √2 render inline. The sum formula renders as its own block. The text around them is intact. |
| 7 | `ctl send-maths md-detect` (bot-style CommonMark HTML) | E=mc² and a b (with a thin space) render inline, and ½ renders as a display block. No literal `(a,b)` or `[\frac{1}{2}]` text remains. |
| 8 | `ctl send-maths no-maths` | The text reads exactly "Tickets are $5/$10; I paid $5 and $10; $x$; use `$x$`" with inline code. Nothing is typeset. |
| 9 | `ctl send-maths spec-invalid`, then `spec-macro` | The TeX shows in the inline-code fallback, with the surrounding text intact. The app does not crash (`pidof io.element.android.x.debug` unchanged). |
| 10 | Switch the system to dark mode and return to the room. | The formulas from steps 2–7 are light on dark, and red stays red. Restore light mode afterwards. |
| 11 | Long-press the step-2 message → Copy text, and read it back by pasting into the composer (do not send). | It equals the fixture `body` `Euler $e^{i\pi}+1=0$ holds`. Clear the composer. |
| 12 | Long-press the step-7 message → Reply. | The reply preview shows plain text, no crash. Cancel without sending. |
| 13 | Developer options → enable "New timeline event renderer", then reopen the room. | Steps 2, 3, 5, 6, 7 and 9 render equivalently. The `spec-wide` display block scrolls horizontally inside the bubble, and its right end is reachable by swiping. Step 6 (plain body) renders through the Compose renderer, because a render DOM exists. |
| 14 | Disable the new renderer again, then reopen the room. | The legacy rendering from steps 2–9 is back unchanged. |
| 15 | `ctl send-maths canary`, then capture a bounded logcat (`--lines 400`, app PID). | Zero matches for `E2ELATEXCANARY7Qx`, the no-content-logging check. |

Cleanup:

- restore dark mode and the "New timeline event renderer" developer option;
- `ctl shutdown`;
- `adb -s emulator-5584 reverse --remove tcp:18028`;
- `synapse-down.sh`;
- shut down only the AVD started here.

Classify each step as passed, failed or blocked. Missing credentials, the AAR or the emulator are blockers, not passes.

## 6. Decisions (closed)

1. The L1 fix behaviour in R3 is accepted: scale to fit with a 0.4 floor in the legacy renderer, and horizontal scroll
   in Compose.
2. Limitations L2–L6 are accepted, including that a delimiter-detection false positive cannot be turned off by the
   user.

Note for implementers: `AGENTS.md` "Feature Defaults" was changed in this worktree (uncommitted) at the user's request.
Commit it with T1, or as its own commit.
