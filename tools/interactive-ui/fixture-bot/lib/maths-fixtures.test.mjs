// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { Parser, HtmlRenderer } from 'commonmark';
import { mathsFixture, mathsFixtureNames } from './maths-fixtures.mjs';

// Python's standard HTML parser avoids a second npm HTML dependency. Pass synthetic HTML via stdin, never command arguments.
function parseHtml(html) {
    return JSON.parse(execFileSync('python3', ['-c', String.raw`
import json, sys
from html.parser import HTMLParser
class FixtureParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack, self.maths, self.text = [], [], []
    def handle_starttag(self, tag, attrs):
        self.stack.append(tag)
        attrs = dict(attrs)
        if 'data-mx-maths' in attrs:
            assert tag in ('span', 'div')
            self.maths.append({'tag': tag, 'tex': attrs['data-mx-maths']})
    def handle_endtag(self, tag):
        assert self.stack.pop() == tag
    def handle_data(self, data):
        self.text.append(data)
p = FixtureParser()
p.feed(sys.stdin.read())
p.close()
assert not p.stack
print(json.dumps({'maths': p.maths, 'text': ''.join(p.text)}))
`], { input: html, encoding: 'utf8' }));
}

test('maths fixture inventory and Matrix message envelopes are exact and independent', () => {
    assert.deepEqual(mathsFixtureNames, ['spec-inline', 'spec-display', 'spec-wide', 'spec-mixed', 'spec-invalid',
        'spec-macro', 'plain-detect', 'md-detect', 'no-maths', 'canary']);
    for (const name of mathsFixtureNames) {
        const content = mathsFixture(name);
        assert.equal(content.msgtype, 'm.text');
        assert.equal(content.format, content.formatted_body ? 'org.matrix.custom.html' : undefined);
        assert.deepEqual(Object.keys(content).sort(), content.formatted_body
            ? ['body', 'format', 'formatted_body', 'msgtype'] : ['body', 'msgtype']);
        content.body = 'changed';
        assert.notEqual(mathsFixture(name).body, 'changed');
    }
    for (const name of ['unknown', '__proto__', 'constructor', '../spec-inline']) assert.throws(() => mathsFixture(name), /Unknown maths fixture/);
});

test('every formatted fixture parses as balanced HTML; spec attributes and fallback preserve exact TeX', () => {
    const expected = {
        'spec-inline': [String.raw`e^{i\pi}+1=0`],
        'spec-display': [String.raw`\int_0^1 x\,dx = \frac{1}{2}`],
        'spec-wide': [mathsFixture('spec-wide').body.slice(2, -2)],
        'spec-mixed': ['a^2', String.raw`\color{red}{x}`, 'b_1'],
        'spec-invalid': [String.raw`\frac{`],
        'spec-macro': [String.raw`\def\a{x}\a`],
    };
    for (const name of mathsFixtureNames) {
        const content = mathsFixture(name);
        if (!content.formatted_body) continue;
        const parsed = parseHtml(content.formatted_body);
        assert.deepEqual(parsed.maths.map(m => m.tex), expected[name] ?? []);
        for (const maths of parsed.maths) {
            assert.equal(maths.tag, ['spec-display', 'spec-wide'].includes(name) ? 'div' : 'span');
            assert.ok(parsed.text.includes(maths.tex));
        }
    }
});

test('bot Markdown fixtures match CommonMark 0.31.2 HTML, omitting only its final newline', () => {
    const parser = new Parser();
    const renderer = new HtmlRenderer();
    for (const name of ['md-detect', 'no-maths']) {
        const content = mathsFixture(name);
        assert.equal(renderer.render(parser.parse(content.body)), content.formatted_body + '\n');
        assert.equal(parseHtml(content.formatted_body).maths.length, 0);
    }
});

test('plain detection and canary preserve raw delimiters and line breaks without HTML', () => {
    assert.equal(mathsFixture('plain-detect').body, String.raw`Area $\pi r^2$, root \(\sqrt{2}\)
$$\sum_{k=1}^n k = \frac{n(n+1)}{2}$$`);
    assert.equal(mathsFixture('canary').body, String.raw`$\text{E2ELATEXCANARY7Qx}$`);
    assert.equal(mathsFixture('spec-inline').body, String.raw`Euler $e^{i\pi}+1=0$ holds`);
});
