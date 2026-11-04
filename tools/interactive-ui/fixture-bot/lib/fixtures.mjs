// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

export const UI_KEY = 'net.15cm.matrix.interactive_ui';
export const ACTION_KEY = `${UI_KEY}.action`;
const root = new URL('../../../../libraries/interactiveui/core/src/test/resources/interactive-ui/fixtures/', import.meta.url);

export async function fixture(name, invalid = false) {
    if (!/^[a-z0-9.-]+$/.test(name) || name.includes('..')) throw new Error('Invalid fixture name');
    return JSON.parse(await readFile(new URL(`${invalid ? 'invalid' : 'valid'}/${name}.json`, root), 'utf8'));
}

// Strict JSON pointer replacement, with /nodes/<id>/... as a convenience for catalog nodes.
// Reject typos instead of silently creating a payload which tests a different scenario.
export function patch(payload, assignment) {
    const split = assignment.indexOf('=');
    if (split < 1) throw new Error('Expected /pointer=JSON');
    const path = assignment.slice(0, split);
    if (!path.startsWith('/')) throw new Error('Expected JSON pointer');
    const keys = path.slice(1).split('/').map(k => k.replace(/~1/g, '/').replace(/~0/g, '~'));
    if (keys.some(k => ['__proto__', 'prototype', 'constructor'].includes(k))) throw new Error('Unsafe pointer');
    let target = payload;
    for (const key of keys.slice(0, -1)) {
        target = Array.isArray(target) && !/^\d+$/.test(key) ? target.find(n => n.id === key) : target[key];
        if (!target || typeof target !== 'object') throw new Error('Unknown pointer');
    }
    target[keys.at(-1)] = JSON.parse(assignment.slice(split + 1));
    return payload;
}

export function fallback(name) { return `Complete text fallback: ${name}`; }
export function message(payload, name, thread) {
    return {
        msgtype: 'm.text', body: fallback(name), [UI_KEY]: payload,
        ...(thread ? { 'm.relates_to': { rel_type: 'm.thread', event_id: thread, is_falling_back: false,
            'm.in_reply_to': { event_id: thread } } } : {}),
    };
}
export function uniquePayload(payload) {
    if (payload && typeof payload === 'object' && !Array.isArray(payload)) payload.ui_id = `e2e-${randomUUID()}`;
    return payload;
}
export function revisionContent(original, payload) {
    const next = message(payload, 'edited');
    return { ...next, 'm.new_content': next, 'm.relates_to': { rel_type: 'm.replace', event_id: original } };
}

export function applyImages(payload, uploads, corrupt = false, offset = 0) {
    uploads = uploads.slice(offset);
    if (uploads.length === 0) throw new Error('Upload encrypted PNGs first');
    for (const node of payload.nodes) {
        if (node.type === 'carousel') {
            if (uploads.length < node.items.length) throw new Error('Upload one PNG per carousel item');
            node.items.forEach((item, i) => { item.image = { ...item.image, file: structuredClone(uploads[i]), mimetype: 'image/png' }; delete item.image.url; });
        }
        if (node.type === 'image') {
            delete node.url;
            node.file = structuredClone(uploads[0]);
            if (corrupt) {
                const bad = structuredClone(node);
                bad.id = `${node.id}-corrupt`;
                bad.alt = 'Corrupted encrypted image';
                if (uploads.length < 2) throw new Error('Upload a separate PNG for corruption');
                bad.file = structuredClone(uploads[1]);
                bad.file.hashes.sha256 = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
                payload.nodes.push(bad);
                payload.nodes.find(n => n.id === payload.root).children.push(bad.id);
                // Only duplicate the original image, not the appended corrupt copy.
                break;
            }
        }
    }
    return payload;
}
