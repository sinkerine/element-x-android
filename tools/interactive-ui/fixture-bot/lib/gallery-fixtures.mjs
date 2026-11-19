// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { encryptAttachment, decryptAttachment, solidPng } from './attachment.mjs';
import { hasPngSignature, pngObservation } from './png-evidence.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
function pdfFixture() {
    const stream = 'BT /F1 12 Tf 20 100 Td (Mixed gallery report) Tj ET\n';
    const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}endstream`];
    let text = '%PDF-1.4\n';
    const offsets = [0];
    objects.forEach((object, i) => { offsets.push(Buffer.byteLength(text)); text += `${i + 1} 0 obj\n${object}\nendobj\n`; });
    const xref = Buffer.byteLength(text);
    text += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}`;
    return Buffer.from(`${text}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
}
function wavFixture() {
    const bytes = Buffer.alloc(44 + 16000);
    bytes.write('RIFF'); bytes.writeUInt32LE(bytes.length - 8, 4); bytes.write('WAVEfmt ', 8);
    bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22);
    bytes.writeUInt32LE(8000, 24); bytes.writeUInt32LE(16000, 28); bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34);
    bytes.write('data', 36); bytes.writeUInt32LE(16000, 40);
    for (let i = 0; i < 8000; i++) bytes.writeInt16LE(Math.round(12000 * Math.sin(2 * Math.PI * 440 * i / 8000)), 44 + i * 2);
    return bytes;
}
export function galleryFixtures() {
    return [
        { name: 'red.png', mime: 'image/png', bytes: solidPng(0) },
        { name: 'green.png', mime: 'image/png', bytes: solidPng(1) },
        { name: 'report.pdf', mime: 'application/pdf', bytes: pdfFixture() },
        { name: 'notes.txt', mime: 'text/plain', bytes: Buffer.from('mixed-file-gallery notes\n') },
        { name: 'tone.wav', mime: 'audio/wav', bytes: wavFixture() },
        { name: 'noext', mime: 'application/octet-stream', bytes: Buffer.from([0, 1, 2, 3, 255]) },
        ...Array.from({ length: 61 }, (_, i) => {
            const name = `bulk/bulk-${String(i + 1).padStart(2, '0')}.txt`;
            return { name, mime: 'text/plain', bytes: Buffer.from(`mixed-file-gallery ${name}\n`) };
        }),
    ];
}
export async function writeDeviceFixtures(out) {
    if (!out) throw new Error('device-fixtures requires --out');
    const files = [];
    for (const { name, mime, bytes } of galleryFixtures()) {
        const path = join(out, name);
        await mkdir(dirname(path), { recursive: true });
        await writeFile(path, bytes);
        files.push({ name, mime, size: bytes.length, sha256: sha256(bytes), png: mime === 'image/png' ? pngObservation(bytes) : null });
    }
    const manifest = { files };
    await writeFile(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    return manifest;
}
export function evidenceAllowed(path) {
    const target = resolve(path);
    return ['interactive-ui', 'latex', 'mixed-file-gallery'].some(name => target.startsWith(resolve('build/e2e', name) + '/'));
}

// Check room state before sending and independently check the server's wire event afterwards.
// Only the separately created plaintext fixture room can bypass encryption.
export async function sendFixtureContent(client, roomId, plainRoomId, content) {
    if (!roomId) throw new Error('Run setup-room first');
    if (roomId !== plainRoomId && !client.getRoom(roomId)?.hasEncryptionStateEvent()) throw new Error('Fixture room must be encrypted');
    const result = await client.sendEvent(roomId, 'm.room.message', content);
    const wire = await client.fetchRoomEvent(roomId, result.event_id);
    const observation = { event_id: result.event_id, wire_type: wire.type, encrypted: wire.type === 'm.room.encrypted' };
    if (!observation.encrypted && roomId !== plainRoomId) throw new Error('Server stored an unencrypted fixture event');
    return observation;
}
function parseOptions(values, allowed) {
    const result = {};
    for (let i = 0; i < values.length; i++) {
        const key = values[i];
        if (!Object.hasOwn(allowed, key) || Object.hasOwn(result, key)) throw new Error('Unknown or duplicate gallery option');
        if (allowed[key]) {
            const value = values[++i];
            if (value === undefined || value.startsWith('--') || (value === '' && key !== '--caption')) throw new Error('Missing gallery option value');
            result[key] = value;
        } else result[key] = true;
    }
    return result;
}
function mxcParts(uri) {
    const match = typeof uri === 'string' && /^mxc:\/\/([^/?#]+)\/([^/?#]+)$/.exec(uri);
    if (!match) throw new Error('Expected MXC attachment');
    return match.slice(1);
}
async function projectGallery(client, entry, content, caption, fetchMedia) {
    const items = [];
    for (const item of content.itemtypes) {
        const source = item.file?.url ?? item.url;
        const [server, mediaId] = mxcParts(source);
        const url = new URL(`/_matrix/client/v1/media/download/${encodeURIComponent(server)}/${encodeURIComponent(mediaId)}`, client.getHomeserverUrl());
        const response = await fetchMedia(url.toString(), { headers: { Authorization: `Bearer ${client.getAccessToken()}` },
            redirect: 'error', signal: AbortSignal.timeout(30000) });
        if (!response.ok) throw new Error('Authenticated media download failed');
        const bytes = Buffer.from(await response.arrayBuffer());
        const plaintext = item.file ? decryptAttachment(bytes, item.file) : bytes;
        const file = item.file;
        items.push({ itemtype: item.itemtype, body: item.body, mimetype: item.info?.mimetype ?? null, size: item.info?.size ?? null,
            has_url: typeof item.url === 'string', url_is_mxc: typeof item.url === 'string' && item.url.startsWith('mxc://'),
            has_file: !!file, file_v: file?.v ?? null,
            file_keys_complete: !!(file?.url && file?.key?.k && file?.key?.kty && file?.key?.alg && file?.key?.key_ops &&
                file?.key?.ext !== undefined && file?.iv && file?.hashes?.sha256 && file?.v === 'v2'),
            plaintext_sha256: sha256(plaintext),
            png: item.itemtype === 'm.image' || item.info?.mimetype === 'image/png' || hasPngSignature(plaintext)
                ? pngObservation(plaintext) : null });
    }
    const relation = content['m.relates_to'];
    return { event_id: entry.event_id, wire_type: entry.wire_type, encrypted: entry.encrypted, msgtype: content.msgtype,
        caption_length: typeof content.body === 'string' ? content.body.length : 0,
        caption_equals: caption === undefined ? null : content.body === caption,
        has_formatted_body: typeof content.formatted_body === 'string',
        relation: { rel_type: relation?.rel_type ?? null, event_id: relation?.event_id ?? null },
        mentions: content['m.mentions'] ?? {}, items };
}

// This is the live control parser as well as the test seam; no second test-only command implementation.
export function createGalleryControl({ client, state, aliceId, since, sendContent, fetchMedia = fetch }) {
    return async values => {
        const [command, ...rest] = values;
        if (command === 'setup-room' && rest.includes('--plain')) {
            parseOptions(rest, { '--plain': false });
            let plainRoomId = state.plainRoomId;
            if (!plainRoomId) {
                const result = await client.createRoom({ name: 'Mixed gallery plain', visibility: 'private', invite: [aliceId], preset: 'private_chat' });
                plainRoomId = result.room_id;
            }
            const deadline = Date.now() + 30000;
            while (!client.getRoom(plainRoomId)) {
                if (Date.now() > deadline) throw new Error('Plain room sync timed out');
                await new Promise(resolveReady => setTimeout(resolveReady, 100));
            }
            if (client.getRoom(plainRoomId).hasEncryptionStateEvent()) throw new Error('Plain fixture room is encrypted');
            state.plainRoomId = plainRoomId;
            return { room_id: plainRoomId, encrypted: false };
        }
        if (command === 'send-gallery') {
            if (rest[0] !== 'mixed') throw new Error('Unknown gallery fixture');
            const options = parseOptions(rest.slice(1), { '--thread': true });
            if (!state.roomId || !client.getRoom(state.roomId)?.hasEncryptionStateEvent()) throw new Error('Run encrypted setup-room first');
            const fixtures = galleryFixtures();
            const itemtypes = [];
            for (const name of ['green.png', 'red.png', 'report.pdf', 'tone.wav']) {
                const { mime, bytes } = fixtures.find(file => file.name === name);
                const { ciphertext, file } = encryptAttachment(bytes);
                const upload = await client.uploadContent(ciphertext, { type: 'application/octet-stream', name: 'encrypted-fixture' });
                file.url = upload.content_uri;
                const itemtype = mime.startsWith('image/') ? 'm.image' : mime.startsWith('audio/') ? 'm.audio' : 'm.file';
                itemtypes.push({ itemtype, body: name, info: { mimetype: mime, size: bytes.length,
                    ...(mime === 'image/png' ? { w: 96, h: 64, thumbnail_file: { ...file }, thumbnail_info: { mimetype: mime, size: bytes.length, w: 96, h: 64 } } : {}), ...(mime === 'audio/wav' ? { duration: 1000 } : {}) }, file });
            }
            const thread = options['--thread'];
            return sendContent({ msgtype: 'dm.filament.gallery', body: 'from bot', itemtypes, 'm.mentions': {},
                ...(thread ? { 'm.relates_to': { rel_type: 'm.thread', event_id: thread, is_falling_back: true, 'm.in_reply_to': { event_id: thread } } } : {}) });
        }
        if (command === 'gallery-last') {
            const options = parseOptions(rest, { '--room': true, '--since': true, '--sender': true, '--replace': false, '--caption': true });
            if (options['--room'] && options['--room'] !== 'plain') throw new Error('Unknown gallery room');
            const roomId = options['--room'] === 'plain' ? state.plainRoomId : state.roomId;
            if (!roomId) throw new Error('Run setup-room first');
            const sender = options['--sender'] === 'alice' ? aliceId : options['--sender'];
            const after = since(values);
            const entries = [...state.observed.values()].filter(entry => entry.sequence > after && (!sender || entry.sender === sender))
                .sort((a, b) => b.sequence - a.sequence);
            for (const entry of entries) {
                const event = state.events.get(entry.event_id);
                if (!event || event.getRoomId() !== roomId) continue;
                await client.decryptEventIfNeeded(event);
                if (event.isDecryptionFailure()) throw new Error('Gallery event decryption failed');
                const original = event.getOriginalContent();
                const outerRelation = original['m.relates_to'];
                const replacement = outerRelation?.rel_type === 'm.replace';
                if (!!options['--replace'] !== replacement) continue;
                const content = replacement ? original['m.new_content'] : original;
                if (content?.msgtype !== 'dm.filament.gallery' || !Array.isArray(content.itemtypes)) continue;
                if (!replacement) return projectGallery(client, entry, content, options['--caption'], fetchMedia);
                if (typeof outerRelation.event_id !== 'string' || !outerRelation.event_id.startsWith('$') || outerRelation.event_id.length < 2) {
                    throw new Error('Gallery replacement has no valid target event ID');
                }
                return {
                    ...await projectGallery(client, entry, content, options['--caption'], fetchMedia),
                    replacement_target_event_id: outerRelation.event_id,
                };
            }
            throw new Error('No matching gallery observed');
        }
        return undefined;
    };
}
