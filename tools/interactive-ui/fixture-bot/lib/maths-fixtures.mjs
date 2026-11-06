// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial

// Expanded polynomial for the wide-display journey; its measured width must be checked on-device.
const wideTex = Array.from({ length: 12 }, (_, i) => `a_{${i + 1}}x^{${i + 1}}`).join(' + ') + String.raw` + \cdots + a_{40}x^{40}`;
const spec = (tex, display = false) => {
    const tag = display ? 'div' : 'span';
    return `<${tag} data-mx-maths="${tex}"><code>${tex}</code></${tag}>`;
};
const fixtures = {
    'spec-inline': {
        body: String.raw`Euler $e^{i\pi}+1=0$ holds`,
        formatted_body: `Euler ${spec(String.raw`e^{i\pi}+1=0`)} holds`,
    },
    'spec-display': {
        body: String.raw`$$\int_0^1 x\,dx = \frac{1}{2}$$`,
        formatted_body: spec(String.raw`\int_0^1 x\,dx = \frac{1}{2}`, true),
    },
    'spec-wide': { body: `$$${wideTex}$$`, formatted_body: spec(wideTex, true) },
    'spec-mixed': {
        body: String.raw`- a $a^2$

> $\color{red}{x}$

**b $b_1$**`,
        formatted_body: `<ul><li>a ${spec('a^2')}</li></ul><blockquote>${spec(String.raw`\color{red}{x}`)}</blockquote><p><b>b ${spec('b_1')}</b></p>`,
    },
    'spec-invalid': { body: String.raw`bad $\frac{$`, formatted_body: `bad ${spec(String.raw`\frac{`)}` },
    'spec-macro': { body: String.raw`$\def\a{x}\a$`, formatted_body: spec(String.raw`\def\a{x}\a`) },
    'plain-detect': {
        body: String.raw`Area $\pi r^2$, root \(\sqrt{2}\)
$$\sum_{k=1}^n k = \frac{n(n+1)}{2}$$`,
    },
    'md-detect': {
        body: String.raw`Energy $E=mc^2$ and \(a\,b\)

\[\frac{1}{2}\]`,
        formatted_body: String.raw`<p>Energy $E=mc^2$ and (a,b)</p>
<p>[\frac{1}{2}]</p>`,
    },
    'no-maths': {
        body: 'Tickets are $5/$10; I paid $5 and $10; \\$x\\$; use `$x$`',
        formatted_body: '<p>Tickets are $5/$10; I paid $5 and $10; $x$; use <code>$x$</code></p>',
    },
    canary: { body: String.raw`$\text{E2ELATEXCANARY7Qx}$` },
};

export const mathsFixtureNames = Object.freeze(Object.keys(fixtures));

/** Fresh Matrix message content; no fixture contents are written to logs or control responses. */
export function mathsFixture(name) {
    if (!Object.hasOwn(fixtures, name)) throw new Error('Unknown maths fixture');
    const fixture = fixtures[name];
    return { msgtype: 'm.text', ...fixture, ...(fixture.formatted_body ? { format: 'org.matrix.custom.html' } : {}) };
}
