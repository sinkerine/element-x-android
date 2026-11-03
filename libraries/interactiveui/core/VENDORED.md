# Interactive UI v1 contract

Source: https://github.com/sinkerine/element-web at commit `bcb080171cb312feef37e63007c7ca8cc0a8b77f`.
Original contributor and copyright holder: Copyright 2026 sinkerine.
Licence: `AGPL-3.0-only OR LicenseRef-Element-Commercial` (see repository root LICENSE files).

The schema is copied unchanged from `apps/web/src/interactive-ui/schema/interactive-ui-v1.schema.json`.
The complete fixture directory is copied unchanged from `apps/web/src/interactive-ui/fixtures/`.
`vendored.sha256` records SHA-256 hashes and destination paths for every vendored file.

To update: select a reviewed upstream commit, copy the entire schema and fixture set together,
retain all notices, regenerate the manifest, update this source commit, and run all core tests.
Never edit individual fixtures downstream. Kotlin ports retain source attribution in KDoc;
the Element Creations header follows this fork's approved R15 convention.

At this commit the source has 22 valid and 53 invalid payloads (53 matching expected-error files),
plus the fixture README and schema: 130 manifest entries. The approved Android plan's count of
51 invalid payloads predates this source set; all 53 are retained unchanged.

`src/test/resources/interactive-ui/generate-goldens.cjs` generates the additional Android test data:
run `node libraries/interactiveui/core/src/test/resources/interactive-ui/generate-goldens.cjs /path/to/element-web`
with the source checkout at the commit above and its TypeScript dependency installed. It executes
the actual upstream `action.ts` and `utils/Reply.ts` after TypeScript transpilation; only unused
sanitizer imports and Matrix constants are substituted. The six action goldens cover plain replies,
threads, original IDs after edits, option labels and values, Unicode truncation and number formatting.
The URL oracle transpiles and executes the actual upstream `protocol.ts` with its actual schema and an inert
`logger.warn`, recording exact parse failures (`ok`, `error.path`, `error.code`) or the successful URL output.
A canonical valid fixture guards against a broken transpilation/import setup. The number cases use Node's
`String(number)`. These generated cases are separate from the unchanged vendored set.

HTTPS host validation uses pinned ICU4J 78.3 UTS46 `IDNA`, with Bidi/ContextJ checks and
nontransitional ASCII conversion. Its Unicode 17.0 data matches the corpus generator's Node 24.21.0
(ICU 78.3, Unicode 17.0), independently of Android's platform Unicode version. The same 782 canonical Web URL
cases run on Robolectric APIs 24, 33 and 35. They retain all 181 original URLs, including initial slash/backslash
authority separators, credentials, schema-rejected separators, percent-decoded forbidden host characters, valid
and invalid bracketed IPv6, and newer Unicode hostnames. A bounded cross-product adds literal TAB/LF/CR,
percent-encoded controls and forbidden raw controls in scheme, authority, domain, port and path, with plain hosts,
credentials, invalid hosts and leading separators at short, 2,048-code-point and 2,049-code-point lengths.
Padding counts keep these boundary inputs compact. The reported overlong literal-host-tab regression is also explicit.
URL parsing strips only literal TAB/LF/CR in its separate parser input; original strings still govern control
validation, schema length/pattern checks and model output. Percent-decoded host controls remain forbidden. Before pinning ICU4J, platform ICU
rejected Node-accepted Adlam U+1E900, Georgian Mtavruli U+1C90, Kawi U+11F02 and CJK Extension I
U+2EBF0 on API 24, and Kawi and CJK Extension I on API 33. These cases remain accepted in the corpus.

Schema regexes translate terminal unescaped `$` anchors to Java's absolute-end `\\z` assertion to
preserve ECMAScript's non-multiline semantics, including rejection of trailing U+0085 in identifiers
and MXC URLs. Parser failures retain exact error paths in returned results but log only trusted
error codes, since paths can include arbitrary payload object keys.

The ICU4J 78.3 dependency JAR is 15,156,073 bytes (14.45 MiB). Its non-class resources total
11,218,047 compressed bytes (10.70 MiB) and 28,168,242 uncompressed bytes. These archive measurements
are not an APK size delta: release shrinking and final APK impact still need measurement in an app build.
