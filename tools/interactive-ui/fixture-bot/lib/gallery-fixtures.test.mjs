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
    assert.equal(plain.items[0].has_file, false);
    assert.equal(plain.items[0].has_url, true); assert.equal(plain.items[0].url_is_mxc, true); assert.equal(plain.items[0].plaintext_sha256, sha(bytes));
    const replacement = { ...content, body: 'new caption', formatted_body: '<b>new caption</b>', 'm.relates_to': { rel_type: 'm.thread', event_id: '$thread' } };
    h.observe('$edit', { 'm.new_content': replacement, 'm.relates_to': { rel_type: 'm.replace', event_id: '$original' } }, { room: '!plain', encrypted: false });
    const edited = await h.control(['gallery-last', '--room', 'plain', '--replace', '--caption', 'new caption']);
    assert.equal(edited.event_id, '$edit'); assert.equal(edited.caption_equals, true); assert.equal(edited.has_formatted_body, true);
    assert.deepEqual(edited.relation, { rel_type: 'm.thread', event_id: '$thread' });
    assert.deepEqual(edited.mentions, { user_ids: ['@alice:test'] });
    assert.equal((await h.control(['gallery-last', '--room', 'plain'])).event_id, '$plain');
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
