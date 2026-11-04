#!/usr/bin/env node
// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { createServer } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, appendFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createClient, ClientEvent, RoomEvent, MatrixEventEvent } from 'matrix-js-sdk';
import { fixture, patch, uniquePayload, message, revisionContent, applyImages, ACTION_KEY } from './lib/fixtures.mjs';
import { encryptAttachment, solidPng } from './lib/attachment.mjs';
import { baseUrl, controlUrl, credentialsPath, tokenPath, secureWrite, secureRead, request, provision } from './lib/local.mjs';

const args = process.argv.slice(2);
const option = (values, key) => { const i = values.indexOf(key); return i < 0 ? undefined : values[i + 1]; };
const noop = () => {};
// SDK loggers may contain event bodies or authentication data. Evidence is an explicit redacted projection below.
const quietLogger = { trace: noop, debug: noop, info: noop, warn: noop, error: noop, log: noop, getChild() { return this; } };

async function serve() {
    const evidence = resolve(option(args, '--evidence') ?? `build/e2e/interactive-ui/${new Date().toISOString().replace(/[:.]/g, '-')}`);
    const allowed = resolve('build/e2e/interactive-ui') + '/';
    if (!evidence.startsWith(allowed)) throw new Error('Evidence must be in build/e2e/interactive-ui');
    await mkdir(join(evidence, 'bot'), { recursive: true, mode: 0o700 });
    const accounts = JSON.parse(await secureRead(credentialsPath));
    if (!accounts.alice || !accounts.uibot) throw new Error('Incomplete provisioning');
    const login = await request('/_matrix/client/v3/login', {
        type: 'm.login.password', identifier: { type: 'm.id.user', user: accounts.uibot.userId },
        password: accounts.uibot.password, initial_device_display_name: 'Interactive UI fixture bot',
    });
    const client = createClient({ baseUrl, userId: login.user_id, accessToken: login.access_token, deviceId: login.device_id, logger: quietLogger });
    await client.initRustCrypto({ useIndexedDB: false });
    const crypto = client.getCrypto();
    crypto.globalBlacklistUnverifiedDevices = false;
    await crypto.bootstrapCrossSigning({ setupNewCrossSigning: true,
        authUploadDeviceSigningKeys: async makeRequest => {
            try { await makeRequest(); }
            catch (error) {
                if (error.httpStatus !== 401) throw error;
                await makeRequest({ type: 'm.login.password', identifier: { type: 'm.id.user', user: login.user_id },
                    password: accounts.uibot.password, session: error.data?.session });
            }
        } });
    const observed = new Map();
    const sent = new Map();
    const uploads = [];
    const marks = new Map();
    let roomId;
    let sequence = 0;
    let lastMark;
    async function record(event) {
        if (event.getRoomId() !== roomId || !event.getId()) return;
        const wireType = event.getWireType();
        if (!['m.room.encrypted', 'm.room.message'].includes(wireType)) return;
        let entry = observed.get(event.getId());
        if (!entry) {
            entry = { sequence: ++sequence, event_id: event.getId(), sender: event.getSender(), wire_type: wireType, encrypted: wireType === 'm.room.encrypted' };
            observed.set(event.getId(), entry);
        }
        const content = event.getContent();
        const action = content[ACTION_KEY];
        if (action && !entry.action) {
            const { nonce, ...redactedAction } = action;
            entry.action = redactedAction;
            entry.nonce_length = typeof nonce === 'string' ? nonce.length : null;
            entry.nonce_base64url_32 = typeof nonce === 'string' && /^[A-Za-z0-9_-]{32}$/.test(nonce);
            entry.mentions = content['m.mentions'];
            entry.relation = content['m.relates_to'];
            entry.decrypted_type = event.getType();
            await appendFile(join(evidence, 'bot/actions.jsonl'), JSON.stringify(entry) + '\n', { mode: 0o600 });
        }
    }
    client.on(RoomEvent.Timeline, event => { void record(event).catch(noop); });
    client.on(MatrixEventEvent.Decrypted, event => { void record(event).catch(noop); });
    await client.startClient({ initialSyncLimit: 100 });
    await new Promise((resolveReady, reject) => {
        const timer = setTimeout(() => reject(new Error('Sync readiness timed out')), 30000);
        const handler = state => {
            if (state === 'PREPARED' || state === 'SYNCING') { clearTimeout(timer); client.off(ClientEvent.Sync, handler); resolveReady(); }
        };
        client.on(ClientEvent.Sync, handler);
        handler(client.getSyncState());
    });
    async function sendContent(content) {
        if (!roomId) throw new Error('Run setup-room first');
        const result = await client.sendEvent(roomId, 'm.room.message', content);
        // Fetch from Synapse: prove the actual server event type, independently of local echo/room state.
        const wire = await client.fetchRoomEvent(roomId, result.event_id);
        const observation = { event_id: result.event_id, wire_type: wire.type, encrypted: wire.type === 'm.room.encrypted' };
        await appendFile(join(evidence, 'bot/sent.jsonl'), JSON.stringify(observation) + '\n', { mode: 0o600 });
        if (!observation.encrypted) throw new Error('Server stored an unencrypted fixture event');
        return observation;
    }
    function since(values) {
        const mark = option(values, '--since');
        if (!mark) return 0;
        const id = mark === 'mark' ? lastMark : mark;
        if (!marks.has(id)) throw new Error('Unknown mark');
        return marks.get(id);
    }
    async function control(values) {
        const [command, name] = values;
        if (command === 'setup-room') {
            if (!roomId) {
                ({ room_id: roomId } = await client.createRoom({ name: 'Interactive UI e2e', visibility: 'private',
                    invite: [accounts.alice.userId], preset: 'private_chat',
                    initial_state: [{ type: 'm.room.encryption', state_key: '', content: { algorithm: 'm.megolm.v1.aes-sha2' } }] }));
                const deadline = Date.now() + 30000;
                while (!client.getRoom(roomId)?.hasEncryptionStateEvent()) {
                    if (Date.now() > deadline) throw new Error('Encrypted room sync timed out');
                    await new Promise(r => setTimeout(r, 100));
                }
            }
            return { room_id: roomId, encrypted: client.getRoom(roomId).hasEncryptionStateEvent() };
        }
        if (command === 'mark') {
            lastMark = `mark-${marks.size + 1}`; marks.set(lastMark, sequence);
            return { mark: lastMark, sequence };
        }
        if (command === 'count-events' || command === 'actions') {
            const sender = option(values, '--sender');
            const senderId = accounts[sender]?.userId ?? sender;
            const entries = [...observed.values()].filter(e => e.sequence > since(values) && (!senderId || e.sender === senderId));
            return command === 'count-events' ? { count: entries.length } : { actions: entries.filter(e => e.action) };
        }
        if (command === 'send-root') return sendContent({ msgtype: 'm.text', body: name ?? 'Thread question' });
        if (command === 'upload-encrypted-png') {
            const { ciphertext, file } = encryptAttachment(solidPng(uploads.length));
            const upload = await client.uploadContent(ciphertext, { type: 'application/octet-stream', name: 'encrypted-fixture' });
            file.url = upload.content_uri;
            uploads.push(file);
            // Keys remain in the bot. A public control response reports only index and URI.
            return { index: uploads.length - 1, url: file.url };
        }
        if (command === 'send' || command === 'send-invalid' || command === 'send-version') {
            let payload = await fixture(command === 'send-version' ? 'knowledge-map.en' : name, command === 'send-invalid');
            payload = uniquePayload(payload);
            if (command === 'send-version') payload.version = Number(name);
            for (let i = 2; i < values.length; i++) if (values[i] === '--set') patch(payload, values[++i]);
            if (values.includes('--encrypted-images')) applyImages(payload, uploads, values.includes('--corrupt-image'), Number(option(values, '--image-offset') ?? 0));
            const thread = option(values, '--thread');
            const result = await sendContent(message(payload, name, thread));
            sent.set(result.event_id, { payload, thread });
            return result;
        }
        if (command === 'revise') {
            const original = sent.get(name);
            if (!original) throw new Error('Unknown original UI event');
            const payload = structuredClone(original.payload);
            payload.revision++;
            payload.status = option(values, '--status') ?? payload.status;
            if (option(values, '--status-text')) payload.status_text = option(values, '--status-text');
            for (let i = 2; i < values.length; i++) if (values[i] === '--set') patch(payload, values[++i]);
            const result = await sendContent(revisionContent(name, payload));
            original.payload = payload;
            return result;
        }
        if (command === 'status') return { ready: true, room_id: roomId, uploads: uploads.length, observations: observed.size };
        if (command === 'shutdown') {
            if (roomId) { await client.leave(roomId); await client.forget(roomId); }
            setTimeout(() => { client.stopClient(); server.close(); process.exit(0); }, 100);
            return { stopped: true };
        }
        throw new Error('Unknown control command');
    }
    const token = randomBytes(32).toString('base64url');
    const server = createServer(async (req, res) => {
        const supplied = Buffer.from(req.headers.authorization ?? '');
        const expected = Buffer.from(`Bearer ${token}`);
        if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) { res.writeHead(401).end(); return; }
        if (req.method !== 'POST' || req.url !== '/ctl') { res.writeHead(404).end(); return; }
        try {
            let body = '';
            for await (const chunk of req) { body += chunk; if (body.length > 65536) throw new Error('Request too large'); }
            const input = JSON.parse(body);
            if (!Array.isArray(input.args) || input.args.some(v => typeof v !== 'string')) throw new Error('Expected string args');
            const output = await control(input.args);
            res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(output));
        } catch (error) {
            // Never serialize SDK exception data (may contain credentials or content).
            res.writeHead(400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: error.name }));
        }
    });
    await new Promise((ok, fail) => { server.once('error', fail); server.listen(18029, '127.0.0.1', ok); });
    await secureWrite(tokenPath, token);
    console.log(JSON.stringify({ ready: true, control: controlUrl, evidence }));
    const stop = () => { client.stopClient(); server.close(); process.exit(0); };
    process.on('SIGTERM', stop); process.on('SIGINT', stop);
}
async function main() {
    if (args[0] === 'provision') console.log(JSON.stringify(await provision()));
    else if (args[0] === 'serve') await serve();
    else if (args[0] === 'ctl') {
        const token = (await secureRead(tokenPath)).trim();
        const response = await fetch(`${controlUrl}/ctl`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ args: args.slice(1) }), signal: AbortSignal.timeout(60000) });
        const result = await response.json();
        if (!response.ok) throw new Error(`Control request HTTP ${response.status} ${result.error}`);
        console.log(JSON.stringify(result));
    } else throw new Error('Usage: bot.mjs provision | serve [--evidence build/e2e/interactive-ui/<UTC>] | ctl <command>');
}
main().catch(error => { console.error(`Fixture bot failed: ${error.name}`); process.exitCode = 1; });
