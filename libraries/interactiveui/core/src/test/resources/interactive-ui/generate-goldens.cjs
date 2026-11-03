/* Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Run: node generate-goldens.cjs /path/to/pinned/element-web
 */
const fs = require('node:fs');
const path = require('node:path');
const web = process.argv[2];
const ts = require(path.join(web, 'node_modules/typescript'));
const source = path.join(web, 'apps/web/src/interactive-ui');
function compile(file, dependencies) {
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText;
    const exports = {};
    new Function('exports', 'require', code)(exports, name => {
        if (!(name in dependencies)) throw Error(`Unexpected import: ${name}`);
        return dependencies[name];
    });
    return exports;
}
// Only sanitizer dependencies used by unrelated Reply exports are inert; reply mixin code executes unchanged.
const key = 'net.15cm.matrix.interactive_ui.action';
const thread = {name: 'm.thread'};
const reply = compile(path.join(web, 'apps/web/src/utils/Reply.ts'), {
    'matrix-js-sdk/src/matrix': {THREAD_RELATION_TYPE: thread, RelationType: {Thread: 'm.thread'}},
    'sanitize-html': {},
    '@element-hq/element-web-shared-utils': {PERMITTED_URL_SCHEMES: []},
});
const action = compile(path.join(source, 'action.ts'), {
    'matrix-js-sdk/src/matrix': {MsgType: {Text: 'm.text'}, THREAD_RELATION_TYPE: thread},
    '../utils/Reply': reply,
    './protocol': {INTERACTIVE_UI_ACTION_KEY: key, INTERACTIVE_UI_VERSION: 1},
});
const fixture = name => JSON.parse(fs.readFileSync(path.join(source, 'fixtures/valid', name + '.json')));
const cases = [
    {name: 'plain', fixture: 'knowledge-map.en', values: {focus: 'maillard'}, triggerLabel: 'Explain this mechanism'},
    {name: 'thread', fixture: 'knowledge-map.en', values: {focus: 'maillard'}, triggerLabel: 'Explain this mechanism', threadRootId: '$thread'},
    {name: 'edited-original', fixture: 'knowledge-map.en', values: {focus: 'maillard'}, triggerLabel: 'Explain', threadRootId: '$thread', revision: 2},
    {name: 'labels-and-values', fixture: 'controls', values: {plan: 'plus', features: ['a', 'b'], enabled: true, name: null, quantity: 2}, triggerLabel: 'Submit'},
    {name: 'unicode-truncation', fixture: 'controls', values: {}, triggerLabel: '中😀'.repeat(1000)},
    {name: 'number-formatting', fixture: 'controls', values: {a: -0, b: 1e-7, c: 1e-6, d: 1e20, e: 1e21, f: 1000000000000000100, g: 5e-324}, triggerLabel: 'Numbers'},
];
for (const entry of cases) {
    const payload = fixture(entry.fixture);
    if (entry.revision) payload.revision = entry.revision;
    const event = {getId: () => '$original', getSender: () => '@bot:example.org', threadRootId: entry.threadRootId,
        getThread: () => undefined, isThreadRoot: false};
    entry.expected = action.buildActionContent({uiEvent: event, payload, actionId: 'answer', values: entry.values, triggerLabel: entry.triggerLabel, nonce: 'abcdefghijklmnopqrstuvwxyz012345'});
}
fs.writeFileSync(path.join(__dirname, 'action-goldens.json'), JSON.stringify(cases, null, 2) + '\n');
const urls = ['https://example.org/', 'https://例子.测试/路径', 'https://[::1]/', 'https://127.1/', 'https://0x7f000001/',
    'https://example.org:443/', 'https://example.org:65536/', 'https://user@example.org/', 'https://:pass@example.org/',
    'http://example.org/', 'HTTPS://example.org/', 'https:example.org', 'https:///example.org', 'https://example.org/a b',
    'https://example.org/a\tb', 'https://example.org/\u0085', 'https://example.org/\ufeff', 'https://example.org/\u001c',
    'https://exa%6dple.org/', 'https://example.org\\path', 'https://', 'https://[bad]/', 'https://example.org:abc/',
    'https://example.org./', 'https://%00.org/', 'https://example.org/?x=<script>', 'https://example.org/@user',
    ' https://example.org/ ', 'https://256.256.256.256/', 'https://example.org:0/', 'https://@example.org/',
    'https://example.org/#fragment', 'https://example.org/😀', 'https://example.org/%zz', 'https://example.org:00080/',
    ...['1', '0', '0x', '0xffffffff', '0x100000000', '4294967295', '4294967296', '0177.0.0.1', '08',
        '1.2.3.4.5', '1.2.3.256', '1.2.65535', '1.2.65536', '1.16777215', '1.16777216', 'example.1',
        'example.0x1', '1..2', '1.2.3.4.', 'example.org..', '-bad.example', 'bad_.example', '[::ffff:127.0.0.1]',
        '[::]', '[2001:db8::1]', '[::1]:443', '[::1', '[1:2:3:4:5:6:7:8:9]', '[::gg]', '[::1%25eth0]',
        '%5B::1%5D', '%3A%3A1', 'exa%3Ample.org', 'exa%3ample.org', 'exa%253Ample.org', 'exa：mple.org',
        '%65xample.org', 'exa%23mple.org', 'foo^bar.org', 'foo|bar.org', 'foo<bar.org', 'foo>bar.org',
        'foo\u007fbar.org', 'foo\u0085bar.org', '\u200d.example', '\u202e.example', 'faß.de', '.', '..', '...',
        'a..b', 'a'.repeat(64) + '.example', ['a'.repeat(63), 'b'.repeat(63), 'c'.repeat(63), 'd'.repeat(63), 'e'].join('.'),
        '😀.example', 'xn--.example', 'xn--fa-hia.de', 'a\u200cb.example', 'مثال.إختبار',
        '\u{1e900}.example', '\u{1c90}.example', '\u{11f02}.example', '\u{2ebf0}.example'].map(host => `https://${host}/`),
    ...['', '0', '00000', '00065535', '65535', '65536', '-1', '+1', '1.0'].map(port => `https://example.org:${port}/`),
    ...['\\', '\\\\', '\\/', '/\\'].flatMap(separators => [
        'example.org/', 'example.org:0/', '@example.org/', ':@example.org/', 'user@example.org/', ':pass@example.org/',
        'user%40name@example.org/', 'example.org/path@user', 'example.org?user@other.org', '[::1]/', '[::1]:0/',
        '[::gg]/', '%5B::1%5D/', '%3A%3A1/', 'exa%3Ample.org/', 'exa%2Fmple.org/', '256.256.256.256/',
    ].map(authority => `https://${separators}${authority}`)),
    'https:/\\example.org/', 'https:\\\\example.org/', 'https:\\/example.org/', 'https:////example.org/',
    'https:///?example.org', 'https://\\?example.org', 'https://\\#example.org'];
const protocol = compile(path.join(source, 'protocol.ts'), {
    './schema/interactive-ui-v1.schema.json': JSON.parse(fs.readFileSync(path.join(source, 'schema/interactive-ui-v1.schema.json'))),
    'matrix-js-sdk/src/logger': {logger: {warn() {}}},
});
if (!protocol.parseInteractiveUi({[protocol.INTERACTIVE_UI_KEY]: fixture('controls')})?.ok) {
    throw Error('Canonical protocol must accept the controls fixture before generating URL expectations');
}
const urlCases = urls.map((input, index) => ({name: `existing-${index}`, input}));
// Bounded cross-product: each control at each parser position, with competing URL/schema failures.
// Padding is stored as a count to keep boundary cases compact; lengths count the original URL's code points.
const controls = ['\t', '\n', '\r', '%09', '%0a', '%0d', '\u0000', '\u000b', '\u001f', '\u007f'];
const positions = {
    scheme: (control, host) => `ht${control}tps://${host}:443/path`,
    authority: (control, host) => `https:/${control}/${host}:443/path`,
    domain: (control, host) => `https://${host.replace('example', `exa${control}mple`)}:443/path`,
    port: (control, host) => `https://${host}:4${control}43/path`,
    path: (control, host) => `https://${host}:443/pa${control}th`,
};
const authorities = {
    plain: 'example.org',
    credentials: 'user:pass@example.org',
    'invalid-host': 'example%2F.org',
    'leading-separators': '\\/\\example.org',
};
for (const [controlIndex, control] of controls.entries()) {
    for (const [position, makeInput] of Object.entries(positions)) {
        for (const [authority, host] of Object.entries(authorities)) {
            const input = makeInput(control, host);
            for (const length of [0, 2048, 2049]) {
                urlCases.push({name: `control-${controlIndex}-${position}-${authority}-${length || 'short'}`, input,
                    padding: length ? length - [...input].length : 0});
            }
        }
    }
}
urlCases.push({name: 'r3-literal-host-tab-overlong', input: 'https://exa\tmple.org/', padding: 2050});
for (const entry of urlCases) {
    const input = entry.input + 'a'.repeat(entry.padding || 0);
    const payload = {version: 1, ui_id: 'url_test', revision: 0, status: 'active', state: {}, root: 'link',
        nodes: [{id: 'link', type: 'link', label: 'Open', url: input}]};
    const result = protocol.parseInteractiveUi({[protocol.INTERACTIVE_UI_KEY]: payload});
    if (!result) throw Error(`Missing parse result: ${entry.name}`);
    // Preserve the exact failure object; successful cases also assert the parser's unmodified URL output.
    entry.expected = result.ok ? {ok: true, url: result.payload.nodes[0].url} : result;
}
fs.writeFileSync(path.join(__dirname, 'url-cases.json'), JSON.stringify(urlCases, null, 2) + '\n');
const numbers = [0, -0, 1, -1, 1e-7, 1e-6, 1e20, 1e21, 1e23, 5e-324, 2.2250738585072014e-308, Number.MAX_VALUE,
    1000000000000000100, 0.30000000000000004, 9007199254740991, 1.2345678901234567];
let seed = 137;
for (let i = 0; i < 2000; i++) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    const number = (seed / 4294967296 - 0.5) * Math.pow(10, i % 630 - 315);
    if (Number.isFinite(number)) numbers.push(number);
}
fs.writeFileSync(path.join(__dirname, 'number-cases.json'), JSON.stringify(numbers.map(value => ({value, expected: String(value)})), null, 2) + '\n');
