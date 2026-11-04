// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { inflateSync } from 'node:zlib';
import { fixture, patch, message, fallback, uniquePayload, revisionContent, applyImages, UI_KEY } from './fixtures.mjs';
import { encryptAttachment, decryptAttachment, solidPng } from './attachment.mjs';

test('AES-256-CTR attachment round trip, Matrix v2 descriptor and corruption rejection', () => {
    const bytes = solidPng(0);
    const { ciphertext, file } = encryptAttachment(bytes);
    assert.notDeepEqual(ciphertext, bytes);
    assert.equal(file.v, 'v2');
    assert.equal(Buffer.from(file.iv, 'base64').subarray(8).equals(Buffer.alloc(8)), true);
    assert.equal(Buffer.from(file.key.k, 'base64url').length, 32);
    assert.equal(file.key.alg, 'A256CTR');
    assert.deepEqual(decryptAttachment(ciphertext, file), bytes);
    ciphertext[10] ^= 1;
    assert.throws(() => decryptAttachment(ciphertext, file), /hash mismatch/);
});
test('five distinct PNGs contain the intended solid RGB pixel data', () => {
    const hashes = new Set();
    for (let i = 0; i < 5; i++) {
        const png = solidPng(i);
        assert.equal(png.subarray(1, 4).toString(), 'PNG');
        assert.equal(png.readUInt32BE(16), 96);
        assert.equal(png.readUInt32BE(20), 64);
        const size = png.readUInt32BE(33);
        const rows = inflateSync(png.subarray(41, 41 + size));
        assert.equal(rows.length, 64 * 289);
        for (let y = 0; y < 64; y++) {
            assert.equal(rows[y * 289], 0);
            for (let x = 0; x < 96; x++) assert.deepEqual(rows.subarray(y * 289 + 1 + x * 3, y * 289 + 4 + x * 3), rows.subarray(1, 4));
        }
        hashes.add(createHash('sha256').update(png).digest('hex'));
    }
    assert.equal(hashes.size, 5);
});
test('vendored fixture patch by node id and numeric index; independent copies and typo rejection', async () => {
    const payload = await fixture('controls');
    patch(payload, '/nodes/submit/action/single_use=true');
    assert.equal(payload.nodes.find(n => n.id === 'submit').action.single_use, true);
    patch(payload, '/nodes/1/label="Plan patched"');
    assert.equal(payload.nodes[1].label, 'Plan patched');
    assert.equal((await fixture('controls')).nodes[1].label, 'Plan');
    assert.throws(() => patch(payload, '/nodes/missing/action/single_use=true'), /Unknown pointer/);
    assert.throws(() => patch(payload, '/__proto__/bad=true'), /Unsafe/);
    await assert.rejects(fixture('../controls'), /Invalid fixture name/);
});
test('fallback, fresh UI identity, thread relation and replacement preserve original event id', async () => {
    const a = uniquePayload(await fixture('knowledge-map.en'));
    const b = uniquePayload(await fixture('knowledge-map.en'));
    assert.notEqual(a.ui_id, b.ui_id);
    const content = message(a, 'knowledge', '$root');
    assert.equal(content.msgtype, 'm.text');
    assert.equal(content.body, fallback('knowledge'));
    assert.equal(content[UI_KEY].version, 1);
    assert.deepEqual(content['m.relates_to'], { rel_type: 'm.thread', event_id: '$root', is_falling_back: false, 'm.in_reply_to': { event_id: '$root' } });
    const edit = revisionContent('$original', { ...a, revision: 1, status: 'submitted' });
    assert.equal(edit['m.new_content'][UI_KEY].revision, 1);
    assert.deepEqual(edit['m.relates_to'], { rel_type: 'm.replace', event_id: '$original' });
    assert.equal(edit[UI_KEY].ui_id, a.ui_id);
});
test('encrypted carousel and corrupt-content variants replace all placeholder media without mutating descriptors', async () => {
    const uploads = Array.from({ length: 5 }, (_, i) => ({ ...encryptAttachment(solidPng(i)).file, url: `mxc://interactive-ui.test/${i}` }));
    const carousel = applyImages(await fixture('product-carousel'), uploads);
    carousel.nodes[1].items.forEach((item, i) => assert.deepEqual(item.image.file, uploads[i]));
    assert.throws(() => applyImages(carousel, uploads.slice(0, 1)), /one PNG per/);
    const content = applyImages(await fixture('content'), uploads, true);
    const good = content.nodes.find(n => n.id === 'image');
    const bad = content.nodes.find(n => n.id === 'image-corrupt');
    assert.equal(good.url, undefined);
    assert.equal(bad.file.hashes.sha256, 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
    assert.notEqual(uploads[0].hashes.sha256, bad.file.hashes.sha256);
    assert.notEqual(good.file.url, bad.file.url);
    assert.equal(bad.file.url, uploads[1].url);
    assert.throws(() => applyImages(structuredClone(content), uploads.slice(0, 1), true), /separate PNG/);
    const offsetContent = applyImages(await fixture('content'), uploads, true, 2);
    assert.equal(offsetContent.nodes.find(n => n.id === 'image').file.url, uploads[2].url);
    assert.equal(offsetContent.nodes.find(n => n.id === 'image-corrupt').file.url, uploads[3].url);
    assert.ok(content.nodes[0].children.includes(bad.id));
});
