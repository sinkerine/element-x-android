// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { deflateSync } from 'node:zlib';
const b64 = bytes => bytes.toString('base64').replace(/=+$/, '');
export function encryptAttachment(bytes) {
    const key = randomBytes(32);
    const iv = Buffer.concat([randomBytes(8), Buffer.alloc(8)]);
    const cipher = createCipheriv('aes-256-ctr', key, iv);
    const ciphertext = Buffer.concat([cipher.update(bytes), cipher.final()]);
    return { ciphertext, file: { v: 'v2', iv: b64(iv), hashes: { sha256: b64(createHash('sha256').update(ciphertext).digest()) },
        key: { kty: 'oct', alg: 'A256CTR', ext: true, key_ops: ['encrypt', 'decrypt'], k: key.toString('base64url') } } };
}
export function decryptAttachment(bytes, file) {
    const actual = createHash('sha256').update(bytes).digest();
    const expected = Buffer.from(file.hashes.sha256, 'base64');
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error('Attachment hash mismatch');
    const decipher = createDecipheriv('aes-256-ctr', Buffer.from(file.key.k, 'base64url'), Buffer.from(file.iv, 'base64'));
    return Buffer.concat([decipher.update(bytes), decipher.final()]);
}
function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
    const name = Buffer.from(type);
    const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([name, data])));
    return Buffer.concat([length, name, data, crc]);
}
// Real 96x64 RGB PNGs; five unmistakable colours for visual decryption checks.
export function solidPng(index) {
    const colours = [[230, 35, 45], [35, 180, 70], [40, 90, 230], [245, 190, 25], [170, 50, 210]];
    const rgb = colours[index % colours.length];
    const header = Buffer.alloc(13); header.writeUInt32BE(96); header.writeUInt32BE(64, 4); header[8] = 8; header[9] = 2;
    const rows = Buffer.alloc(64 * (1 + 96 * 3));
    for (let y = 0; y < 64; y++) for (let x = 0; x < 96; x++) rgb.forEach((v, c) => { rows[y * 289 + 1 + x * 3 + c] = v; });
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
