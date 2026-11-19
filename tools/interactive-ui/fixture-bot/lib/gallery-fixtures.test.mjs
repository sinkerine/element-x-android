// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir, homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { encryptAttachment, decryptAttachment, solidPng } from './attachment.mjs';
import { MatrixEvent } from 'matrix-js-sdk';
import { deflateSync, inflateSync } from 'node:zlib';
const load = () => import('./gallery-fixtures.mjs');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const bot = resolve('bot.mjs');

test('gallery fixtures are deterministic and match the manifest', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'gallery-'));
    try {
        execFileSync(process.execPath, [bot, 'device-fixtures', '--out', dir]);
        const first = await readFile(join(dir, 'manifest.json'), 'utf8');
        const { files } = JSON.parse(first);
        assert.equal(files.length, 67);
        assert.deepEqual(files.slice(0, 6).map(f => f.name), ['red.png', 'green.png', 'report.pdf', 'notes.txt', 'tone.wav', 'noext']);
        for (const file of files) {
            const bytes = await readFile(join(dir, file.name));
            assert.equal(file.size, bytes.length);
            assert.equal(file.sha256, sha(bytes));
            assert.equal(typeof file.mime, 'string');
            if (file.mime === 'image/png') assert.deepEqual(file.png, expectedSolid(file.name === 'red.png' ? [230, 35, 45] : [35, 180, 70]));
            else assert.equal(file.png, null);
        }
        assert.deepEqual(await readFile(join(dir, 'red.png')), solidPng(0));
        assert.deepEqual(await readFile(join(dir, 'green.png')), solidPng(1));
        assert.equal(await readFile(join(dir, 'notes.txt'), 'utf8'), 'mixed-file-gallery notes\n');
        assert.deepEqual(await readFile(join(dir, 'noext')), Buffer.from([0, 1, 2, 3, 255]));
        assert.equal(files.at(-1).name, 'bulk/bulk-61.txt');
        execFileSync(process.execPath, [bot, 'device-fixtures', '--out', dir]);
        assert.equal(await readFile(join(dir, 'manifest.json'), 'utf8'), first);
    } finally { await rm(dir, { recursive: true, force: true }); }
});
test('wav fixture encodes 1 s 8 kHz 16-bit mono', async () => {
    const wav = (await load()).galleryFixtures().find(f => f.name === 'tone.wav').bytes;
    assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
    assert.equal(wav.readUInt32LE(4), wav.length - 8);
    assert.equal(wav.toString('ascii', 8, 16), 'WAVEfmt ');
    assert.equal(wav.readUInt16LE(20), 1);
    assert.equal(wav.readUInt16LE(22), 1);
    assert.equal(wav.readUInt32LE(24), 8000);
    assert.equal(wav.readUInt16LE(34), 16);
    assert.equal(wav.readUInt32LE(40), 16000);
    for (let i = 0; i < 8000; i++) assert.equal(wav.readInt16LE(44 + i * 2), Math.round(12000 * Math.sin(2 * Math.PI * 440 * i / 8000)) || 0);
});
test('pdf fixture is a single-page PDF', async () => {
    const pdf = (await load()).galleryFixtures().find(f => f.name === 'report.pdf').bytes.toString();
    assert.ok(pdf.startsWith('%PDF-1.4'));
    assert.equal((pdf.match(/\/Type \/Page\b/g) ?? []).length, 1);
    assert.match(pdf, /\/Count 1\b/);
    const offset = Number(pdf.match(/startxref\n(\d+)/)[1]);
    assert.equal(pdf.slice(offset, offset + 4), 'xref');
    const rows = pdf.slice(offset).split('\n').slice(3, 8);
    rows.forEach((row, index) => assert.ok(pdf.slice(Number(row.slice(0, 10))).startsWith(`${index + 1} 0 obj`)));
});
function harness(module, overrides = {}) {
    const state = { roomId: '!encrypted', plainRoomId: undefined, observed: new Map(), events: new Map() };
    const uploads = [], sends = [], downloads = [];
    const media = new Map();
    const client = {
        createRoom: async config => { assert.equal(config.name, 'Mixed gallery plain'); assert.deepEqual(config.invite, ['@alice:test']); assert.equal(config.initial_state?.length ?? 0, 0); return { room_id: '!plain' }; },
        getRoom: id => ({ hasEncryptionStateEvent: () => id === '!encrypted' }),
        uploadContent: async (bytes, options) => { uploads.push({ bytes, options }); const uri = `mxc://test/${uploads.length}`; media.set(uri, bytes); return { content_uri: uri }; },
        sendEvent: async (room, type, content) => { sends.push({ room, type, content }); return { event_id: '$sent' }; },
        fetchRoomEvent: async room => ({ type: room === '!encrypted' ? 'm.room.encrypted' : 'm.room.message' }),
        decryptEventIfNeeded: async () => {},
        getHomeserverUrl: () => 'http://127.0.0.1:18028', getAccessToken: () => 'synthetic-token',
        ...overrides,
    };
    const fetchMedia = async (url, options) => {
        downloads.push({ url, options });
        assert.equal(options.headers.Authorization, 'Bearer synthetic-token');
        assert.equal(options.redirect, 'error');
        const id = url.split('/').at(-1);
        const bytes = media.get(`mxc://test/${id}`);
        assert.ok(bytes);
        return { ok: true, arrayBuffer: async () => bytes };
    };
    const sendContent = content => module.sendFixtureContent(client, state.roomId, state.plainRoomId, content);
    const control = module.createGalleryControl({ client, state, aliceId: '@alice:test', since: values => values.includes('--since') ? 2 : 0, sendContent, fetchMedia });
    const observe = (id, content, { room = '!encrypted', sender = '@alice:test', sequence = 3, encrypted = true } = {}) => {
        state.observed.set(id, { event_id: id, sequence, sender, encrypted, wire_type: encrypted ? 'm.room.encrypted' : 'm.room.message' });
        state.events.set(id, { getContent: () => content, getOriginalContent: () => content, getRoomId: () => room, isDecryptionFailure: () => false });
    };
    return { state, client, control, uploads, sends, downloads, media, observe };
}
test('gallery content matches the MSC4274 item schema accepted by Element Web', async () => {
    const h = harness(await load());
    assert.deepEqual(await h.control(['send-gallery', 'mixed', '--thread', '$root']), { event_id: '$sent', wire_type: 'm.room.encrypted', encrypted: true });
    assert.equal(h.sends.length, 1);
    const content = h.sends[0].content;
    const moduleFixtures = (await load()).galleryFixtures();
    assert.equal(content.msgtype, 'dm.filament.gallery'); assert.equal(content.body, 'from bot');
    assert.deepEqual(content['m.mentions'], {});
    assert.equal(content['m.relates_to'].rel_type, 'm.thread'); assert.equal(content['m.relates_to'].event_id, '$root');
    assert.deepEqual(content.itemtypes.map(i => i.body), ['green.png', 'red.png', 'report.pdf', 'tone.wav']);
    assert.deepEqual(content.itemtypes.map(i => i.itemtype), ['m.image', 'm.image', 'm.file', 'm.audio']);
    content.itemtypes.forEach((item, index) => {
        assert.ok(Number.isInteger(item.info.size)); assert.ok(item.info.size > 0);
        assert.equal(item.url, undefined); assert.match(item.file.url, /^mxc:\/\//);
        assert.equal(item.file.v, 'v2'); assert.equal(item.file.key.alg, 'A256CTR');
        assert.equal(h.uploads[index].options.type, 'application/octet-stream');
        assert.ok(item.body.length > 0); assert.equal(typeof item.info.mimetype, 'string');
        assert.deepEqual(item.file.key.key_ops, ['encrypt', 'decrypt']); assert.equal(item.file.key.kty, 'oct');
        assert.ok(item.file.iv && item.file.hashes.sha256 && item.file.key.k);
        const fixture = moduleFixtures.find(f => f.name === item.body);
        assert.deepEqual(decryptAttachment(h.uploads[index].bytes, item.file), fixture.bytes);
    });
    await assert.rejects(h.control(['send-gallery', 'unknown']));
    await assert.rejects(h.control(['send-gallery', 'mixed', '--thread']));
    await assert.rejects(h.control(['send-gallery', 'mixed', '--room', 'plain']));
});
test('gallery projection reports caption equality without caption text', async () => {
    const h = harness(await load());
    await h.control(['send-gallery', 'mixed']);
    const content = h.sends[0].content;
    h.observe('$old', content, { sequence: 1 });
    h.observe('$other-sender', content, { sender: '@other:test' });
    h.observe('$gallery', content);
    const result = await h.control(['gallery-last', '--since', 'mark', '--sender', 'alice', '--caption', 'from bot']);
    assert.equal(result.event_id, '$gallery'); assert.equal(result.caption_equals, true); assert.equal(result.caption_length, 8);
    assert.equal(result.has_formatted_body, false); assert.equal(result.encrypted, true);
    assert.equal(result.items.length, 4); assert.equal(h.downloads.length, 4);
    result.items.forEach((item, i) => {
        assert.equal(item.plaintext_sha256, sha(decryptAttachment(h.uploads[i].bytes, content.itemtypes[i].file)));
        assert.equal(item.file_keys_complete, true); assert.equal(item.has_url, false); assert.equal(item.file_v, 'v2');
    });
    assert.ok(!JSON.stringify(result).includes('from bot'));
    assert.ok(!JSON.stringify(result).includes('synthetic-token'));
    assert.equal((await h.control(['gallery-last', '--caption', 'wrong'])).caption_equals, false);
    assert.equal((await h.control(['gallery-last'])).caption_equals, null);
    await assert.rejects(h.control(['gallery-last', '--sender', '@absent:test']));
    await assert.rejects(h.control(['gallery-last', '--room', 'arbitrary']));
    await assert.rejects(h.control(['gallery-last', '--caption']));
    let decryptionCalls = 0;
    h.client.decryptEventIfNeeded = async () => { decryptionCalls++; };
    await h.control(['gallery-last', '--sender', 'alice']);
    assert.equal(decryptionCalls, 1);
    h.uploads[0].bytes[0] ^= 1;
    await assert.rejects(h.control(['gallery-last', '--sender', 'alice']), /Attachment hash mismatch/);
});
test('gallery-last selects replacements, plaintext rooms, threads and marks through its command parser', async () => {
    const h = harness(await load());
    assert.deepEqual(await h.control(['setup-room', '--plain']), { room_id: '!plain', encrypted: false });
    const bytes = Buffer.from('synthetic'); h.media.set('mxc://test/plain', bytes);
    const item = { itemtype: 'm.file', body: 'notes.txt', info: { size: bytes.length, mimetype: 'text/plain' }, url: 'mxc://test/plain' };
    const content = { msgtype: 'dm.filament.gallery', body: '', itemtypes: [item], 'm.mentions': { user_ids: ['@alice:test'] } };
    h.observe('$plain', content, { room: '!plain', encrypted: false });
    const plain = await h.control(['gallery-last', '--room', 'plain', '--caption', '']);
    assert.equal(plain.event_id, '$plain'); assert.equal(plain.caption_length, 0); assert.equal(plain.caption_equals, true);
    assert.equal(Object.hasOwn(plain, 'replacement_target_event_id'), false);
    assert.equal(plain.items[0].has_file, false);
    assert.equal(plain.items[0].has_url, true); assert.equal(plain.items[0].url_is_mxc, true); assert.equal(plain.items[0].plaintext_sha256, sha(bytes));
    const replacement = { ...content, body: 'new caption', formatted_body: '<b>new caption</b>', 'm.relates_to': { rel_type: 'm.thread', event_id: '$thread' } };
    h.observe('$edit', { 'm.new_content': replacement, 'm.relates_to': { rel_type: 'm.replace', event_id: '$original' } }, { room: '!plain', encrypted: false });
    const edited = await h.control(['gallery-last', '--room', 'plain', '--replace', '--caption', 'new caption']);
    assert.equal(edited.event_id, '$edit'); assert.equal(edited.caption_equals, true); assert.equal(edited.has_formatted_body, true);
    assert.deepEqual(edited.relation, { rel_type: 'm.thread', event_id: '$thread' });
    assert.equal(edited.replacement_target_event_id, '$original');
    assert.deepEqual(edited.mentions, { user_ids: ['@alice:test'] });
    assert.equal((await h.control(['gallery-last', '--room', 'plain'])).event_id, '$plain');
    h.observe('$missing-target', { 'm.new_content': replacement, 'm.relates_to': { rel_type: 'm.replace' } }, { room: '!plain', encrypted: false, sequence: 4 });
    await assert.rejects(h.control(['gallery-last', '--room', 'plain', '--replace']), /no valid target event ID/);
    h.media.set('mxc://test/plain', Buffer.from('changed'));
    h.observe('$bad', { ...content, itemtypes: [{ ...item, file: encryptAttachment(bytes).file, url: undefined }] });
    await assert.rejects(h.control(['gallery-last']));
});
test('gallery-last uses SDK original event content and fails closed on decryption failure', async () => {
    const h = harness(await load());
    await h.control(['send-gallery', 'mixed']);
    const content = h.sends[0].content;
    const original = new MatrixEvent({ event_id: '$original', room_id: '!encrypted', sender: '@alice:test', type: 'm.room.message', content });
    const edit = new MatrixEvent({ event_id: '$edit', room_id: '!encrypted', sender: '@alice:test', type: 'm.room.message',
        content: { 'm.relates_to': { rel_type: 'm.replace', event_id: '$original' }, 'm.new_content': { ...content, body: 'changed caption' } } });
    original.makeReplaced(edit);
    assert.equal(original.getContent().body, 'changed caption');
    h.observe('$original', content);
    h.state.events.set('$original', original);
    assert.equal((await h.control(['gallery-last', '--caption', 'from bot'])).caption_equals, true);
    h.observe('$broken', {}, { sequence: 4 });
    h.state.events.get('$broken').isDecryptionFailure = () => true;
    await assert.rejects(h.control(['gallery-last']), /Gallery event decryption failed/);
});
test('default room retains encrypted-only send guard and plain room is the sole exception', async () => {
    const module = await load();
    const h = harness(module, { fetchRoomEvent: async () => ({ type: 'm.room.message' }) });
    await assert.rejects(module.sendFixtureContent(h.client, '!encrypted', '!plain', { body: 'synthetic' }));
    await assert.rejects(module.sendFixtureContent(h.client, '!arbitrary', '!plain', { body: 'synthetic' }));
    assert.equal((await module.sendFixtureContent(h.client, '!plain', '!plain', { body: 'synthetic' })).encrypted, false);
    const encrypted = harness(module);
    encrypted.client.getRoom = () => ({ hasEncryptionStateEvent: () => false });
    await assert.rejects(module.sendFixtureContent(encrypted.client, '!encrypted', undefined, {}));
    assert.equal(encrypted.sends.length, 0);
    const encryptedPlain = harness(module, { getRoom: () => ({ hasEncryptionStateEvent: () => true }) });
    await assert.rejects(encryptedPlain.control(['setup-room', '--plain']), /Plain fixture room is encrypted/);
    assert.equal(encryptedPlain.state.plainRoomId, undefined);
});
test('evidence allowlist accepts mixed-file-gallery and rejects prefixed siblings', async () => {
    const { evidenceAllowed } = await load();
    for (const name of ['mixed-file-gallery', 'latex', 'interactive-ui']) {
        assert.equal(evidenceAllowed(`build/e2e/${name}/run`), true);
        for (const path of [`build/e2e/${name}-evil/run`, `build/e2e/${name}/../secret`, `build/e2e/${name}`]) assert.equal(evidenceAllowed(path), false);
    }
    assert.throws(() => execFileSync(process.execPath, [bot, 'serve', '--evidence', 'build/e2e/mixed-file-gallery-evil/run'], { stdio: 'pipe' }));
});
test('state directory honours ELEMENT_X_E2E_STATE_DIR', () => {
    const script = 'import {localDir, credentialsPath, tokenPath} from "./lib/local.mjs"; console.log(JSON.stringify({localDir,credentialsPath,tokenPath}))';
    const env = { ...process.env, ELEMENT_X_E2E_STATE_DIR: '/tmp/synthetic-gallery-state' };
    const actual = JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', script], { env, encoding: 'utf8' }));
    assert.deepEqual(actual, { localDir: env.ELEMENT_X_E2E_STATE_DIR, credentialsPath: join(env.ELEMENT_X_E2E_STATE_DIR, 'credentials.json'), tokenPath: join(env.ELEMENT_X_E2E_STATE_DIR, 'control-token') });
    delete env.ELEMENT_X_E2E_STATE_DIR;
    const fallback = JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', script], { env, encoding: 'utf8' }));
    assert.equal(fallback.localDir, join(homedir(), '.local/share/element-x-e2e/interactive-ui'));
});

// Independently encoded by ImageMagick: convert -size 96x64 'xc:rgb(230,35,45)' -depth 8
// -set comment 'independent encoded counterpart' PNG24:counterpart.png. No external runtime dependency.
const counterpart = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAIAAABqVuVZAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAAGYktHRAD/AP8A/6C9p5MAAAAHdElNRQfqCxEBKSiUT86JAAAAJ3RFWHRjb21tZW50AGluZGVwZW5kZW50IGVuY29kZWQgY291bnRlcnBhcnQYNCikAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDI2LTExLTE3VDAxOjQxOjQwKzAwOjAwsHB7mQAAACV0RVh0ZGF0ZTptb2RpZnkAMjAyNi0xMS0xN1QwMTo0MTo0MCswMDowMMEtwyUAAAAodEVYdGRhdGU6dGltZXN0YW1wADIwMjYtMTEtMTdUMDE6NDE6NDArMDA6MDCWOOL6AAAAnElEQVR42u3QMRHAIADAQMBKZ/w7QkcV9DLS4V9BLvM8e/Bt3Q74O4OCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUXto0AbYO4SV1AAAAAElFTkSuQmCC', 'base64');
function expectedSolid(rgb, width = 96, height = 64) {
    return { width, height, rgb_sha256: sha(Buffer.from(Array.from({ length: width * height }, () => rgb).flat())) };
}
function pngChunk(type, data) {
    const name = Buffer.from(type), length = Buffer.alloc(4), checksum = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    let crc = 0xffffffff;
    for (const byte of Buffer.concat([name, data])) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([length, name, data, checksum]);
}
function rewritePng(bytes, transform) {
    const chunks = [];
    for (let offset = 8; offset < bytes.length;) {
        const size = bytes.readUInt32BE(offset);
        chunks.push({ type: bytes.toString('ascii', offset + 4, offset + 8), data: bytes.subarray(offset + 8, offset + 8 + size) });
        offset += size + 12;
    }
    return Buffer.concat([bytes.subarray(0, 8), ...transform(chunks).map(c => pngChunk(c.type, c.data))]);
}
test('PNG evidence accepts independently encoded identical pixels and rejects changed pixels or dimensions', async () => {
    const { pngObservation } = await import('./png-evidence.mjs');
    assert.notEqual(sha(counterpart), sha(solidPng(0)));
    assert.deepEqual(pngObservation(counterpart), expectedSolid([230, 35, 45]));
    assert.deepEqual(pngObservation(counterpart), pngObservation(solidPng(0)));
    assert.notDeepEqual(pngObservation(solidPng(1)), pngObservation(counterpart));
    const resized = rewritePng(solidPng(0), chunks => chunks.map(c => {
        if (c.type === 'IHDR') { c = { ...c, data: Buffer.from(c.data) }; c.data.writeUInt32BE(48, 0); c.data.writeUInt32BE(128, 4); }
        if (c.type === 'IDAT') c = { ...c, data: deflateSync(Buffer.concat(Array.from({ length: 128 }, () => Buffer.concat([Buffer.from([0]), Buffer.from(Array(48).fill([230, 35, 45]).flat())])))) };
        return c;
    }));
    assert.deepEqual(pngObservation(resized), expectedSolid([230, 35, 45], 48, 128));
    assert.notDeepEqual(pngObservation(resized), pngObservation(counterpart));
});
test('PNG evidence handles all five filters, split IDAT and metadata without changing RGB order', async () => {
    const { pngObservation } = await import('./png-evidence.mjs');
    // Hand-calculated 2x5 RGB scanlines: filters None, Sub, Up, Average, Paeth.
    const rows = Buffer.from([
        0, 10,20,30, 40,50,60,
        1, 15,25,35, 30,30,30,
        2, 5,5,5, 5,5,5,
        3, 15,20,25, 15,15,15,
        4, 5,5,5, 5,5,5,
    ]);
    const pixels = Buffer.from([10,20,30,40,50,60, 15,25,35,45,55,65, 20,30,40,50,60,70, 25,35,45,52,62,72, 30,40,50,57,67,77]);
    const bytes = rewritePng(solidPng(0), chunks => chunks.flatMap(c => {
        if (c.type === 'IHDR') { const header = Buffer.from(c.data); header.writeUInt32BE(2); header.writeUInt32BE(5, 4); return [{ ...c, data: header }, { type: 'eXIf', data: Buffer.from('synthetic metadata') }]; }
        if (c.type === 'IDAT') { const data = deflateSync(rows); return [{ ...c, data: data.subarray(0, 5) }, { ...c, data: data.subarray(5) }]; }
        return [c];
    }));
    assert.deepEqual(pngObservation(bytes), { width: 2, height: 5, rgb_sha256: sha(pixels) });
});
test('PNG evidence fails closed for malformed or unsupported encodings', async () => {
    const { pngObservation } = await import('./png-evidence.mjs');
    const original = solidPng(0);
    const badCrc = Buffer.from(original); badCrc[29] ^= 1;
    const mutateHeader = mutate => rewritePng(original, chunks => chunks.map(c => {
        if (c.type !== 'IHDR') return c;
        const data = Buffer.from(c.data); mutate(data); return { ...c, data };
    }));
    const invalid = [Buffer.from('not PNG'), original.subarray(0, -1), badCrc, Buffer.concat([original, Buffer.from([0])]),
        mutateHeader(h => { h[8] = 16; }), mutateHeader(h => { h[9] = 6; }), mutateHeader(h => { h[12] = 1; }),
        mutateHeader(h => h.writeUInt32BE(0)), mutateHeader(h => h.writeUInt32BE(0x7fffffff)),
        rewritePng(original, c => [...c.slice(0, 1), { type: 'tRNS', data: Buffer.alloc(6) }, ...c.slice(1)]),
        rewritePng(original, c => [...c.slice(0, 1), { type: 'ABCD', data: Buffer.alloc(0) }, ...c.slice(1)]),
        rewritePng(original, c => [c[0], c[0], ...c.slice(1)]),
        rewritePng(original, c => c.filter(x => x.type !== 'IDAT')),
        rewritePng(original, c => [c[0], c[1], { type: 'tEXt', data: Buffer.from('key\0value') }, c[1], c[2]]),
        rewritePng(original, c => c.map(x => x.type === 'IDAT' ? { ...x, data: Buffer.concat([x.data, Buffer.from([0])]) } : x)),
        rewritePng(original, c => c.map(x => x.type === 'IDAT' ? { ...x, data: deflateSync(Buffer.from([5])) } : x)),
        rewritePng(original, c => c.map(x => x.type === 'IDAT' ? { ...x, data: deflateSync(Buffer.alloc(64 * 289 + 1)) } : x)),
        rewritePng(original, c => c.map(x => x.type === 'IDAT' ? { ...x, data: Buffer.from('bad zlib') } : x)),
    ];
    for (const bytes of invalid) assert.throws(() => pngObservation(bytes), /PNG/);
    const badFilter = rewritePng(original, c => c.map(x => {
        if (x.type !== 'IDAT') return x;
        const rows = inflateSync(x.data); rows[0] = 5; return { ...x, data: deflateSync(rows) };
    }));
    assert.throws(() => pngObservation(badFilter), /PNG/);
});
test('gallery-last emits PNG observations from actual authenticated decrypted SDK event bytes', async () => {
    const h = harness(await load());
    await h.control(['send-gallery', 'mixed']);
    const content = h.sends[0].content;
    // The expected fixture name and declared dimensions stay unchanged, while received bytes vary.
    const replaceImage = bytes => {
        const { ciphertext, file } = encryptAttachment(bytes);
        file.url = content.itemtypes[1].file.url;
        content.itemtypes[1].file = file; h.media.set(file.url, ciphertext);
    };
    const project = async () => {
        const event = new MatrixEvent({ event_id: '$actual', room_id: '!encrypted', sender: '@alice:test', type: 'm.room.message', content });
        h.observe('$actual', content); h.state.events.set('$actual', event);
        return h.control(['gallery-last', '--sender', 'alice']);
    };
    replaceImage(counterpart);
    const result = await project();
    assert.deepEqual(result.items[1].png, expectedSolid([230, 35, 45]));
    assert.equal(result.items[1].plaintext_sha256, sha(counterpart));
    assert.notEqual(result.items[1].plaintext_sha256, sha(solidPng(0)));
    assert.equal(result.items[2].png, null);
    assert.equal(result.items[2].plaintext_sha256, sha((await load()).galleryFixtures().find(f => f.name === 'report.pdf').bytes));
    assert.ok(h.downloads.every(d => d.options.headers.Authorization === 'Bearer synthetic-token'));
    replaceImage(solidPng(1));
    assert.deepEqual((await project()).items[1].png, expectedSolid([35, 180, 70]));
    replaceImage(Buffer.from('malformed PNG'));
    await assert.rejects(project(), /PNG/);
    replaceImage(rewritePng(counterpart, chunks => chunks.map(c => {
        if (c.type !== 'IHDR') return c;
        const data = Buffer.from(c.data); data[9] = 6; return { ...c, data };
    })));
    await assert.rejects(project(), /PNG/);
    // Plaintext media uses the same decoder after authenticated download.
    await h.control(['setup-room', '--plain']); h.media.set('mxc://test/plain-png', counterpart);
    h.observe('$plain-png', { ...content, itemtypes: [{ itemtype: 'm.image', body: 'arbitrary', info: { mimetype: 'image/png' }, url: 'mxc://test/plain-png' }] }, { room: '!plain', encrypted: false, sequence: 5 });
    assert.deepEqual((await h.control(['gallery-last', '--room', 'plain'])).items[0].png, expectedSolid([230, 35, 45]));
});

test('send-gallery encrypted images expose decryptable thumbnail_file through SDK original content', async () => {
    const h = harness(await load());
    await h.control(['send-gallery', 'mixed']);
    const event = new MatrixEvent({ type: 'm.room.message', content: h.sends[0].content });
    for (const item of event.getOriginalContent().itemtypes.filter(i => i.itemtype === 'm.image')) {
        assert.ok(item.info.thumbnail_file, 'SDK image thumbnail contract requires info.thumbnail_file');
        assert.equal(item.info.thumbnail_url, undefined);
        assert.deepEqual(item.info.thumbnail_info, { mimetype: 'image/png', size: item.info.size, w: 96, h: 64 });
        const encrypted = h.media.get(item.info.thumbnail_file.url);
        assert.ok(encrypted);
        assert.deepEqual(decryptAttachment(encrypted, item.info.thumbnail_file), (await load()).galleryFixtures().find(f => f.name === item.body).bytes);
    }
});
