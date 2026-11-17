// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { createHash } from 'node:crypto';
import { inflateSync } from 'node:zlib';

const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
export const hasPngSignature = bytes => bytes.subarray(0, 8).equals(signature);
const invalid = () => { throw new Error('Malformed or unsupported PNG evidence'); };
function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}
function paeth(left, up, corner) {
    const p = left + up - corner;
    const a = Math.abs(p - left), b = Math.abs(p - up), c = Math.abs(p - corner);
    return a <= b && a <= c ? left : b <= c ? up : corner;
}

// Evidence for the opaque RGB8, non-interlaced fixture PNGs only. Do not silently
// discard alpha, transparency or precision from unsupported encodings.
export function pngObservation(bytes) {
    if (!hasPngSignature(bytes)) invalid();
    let width, height, ended = false, idatEnded = false, palette = false;
    const data = [];
    for (let offset = 8; offset < bytes.length;) {
        if (offset + 12 > bytes.length) invalid();
        const length = bytes.readUInt32BE(offset);
        const end = offset + 12 + length;
        if (end > bytes.length) invalid();
        const type = bytes.toString('latin1', offset + 4, offset + 8);
        if (!/^[A-Za-z]{4}$/.test(type) || type[2] !== type[2].toUpperCase()) invalid();
        const payload = bytes.subarray(offset + 8, end - 4);
        if (crc32(bytes.subarray(offset + 4, end - 4)) !== bytes.readUInt32BE(end - 4)) invalid();
        if (offset === 8 && type !== 'IHDR') invalid();
        if (data.length && type !== 'IDAT') idatEnded = true;
        if (type === 'IHDR') {
            if (offset !== 8 || length !== 13) invalid();
            width = payload.readUInt32BE(0); height = payload.readUInt32BE(4);
            // Bound allocation and inflation even for corrupt or hostile media.
            if (!width || !height || width * height > 16 * 1024 * 1024 ||
                payload[8] !== 8 || payload[9] !== 2 || payload[10] !== 0 || payload[11] !== 0 || payload[12] !== 0) invalid();
        } else if (type === 'IDAT') {
            if (idatEnded) invalid();
            data.push(payload);
        } else if (type === 'IEND') {
            if (length || !data.length || end !== bytes.length) invalid();
            ended = true;
        } else if (type === 'PLTE') {
            if (palette || data.length || !length || length > 768 || length % 3) invalid();
            palette = true; // Optional suggested palette does not alter truecolour RGB samples.
        } else if (['tRNS', 'acTL', 'fcTL', 'fdAT'].includes(type) || type[0] === type[0].toUpperCase()) {
            invalid();
        }
        // Ancillary metadata (including eXIf/text) is not part of the RGB samples.
        offset = end;
    }
    if (!ended) invalid();
    const stride = width * 3, expectedLength = height * (stride + 1);
    let rows;
    try {
        const compressed = Buffer.concat(data);
        const result = inflateSync(compressed, { maxOutputLength: expectedLength, info: true });
        if (result.engine.bytesWritten !== compressed.length) invalid();
        rows = result.buffer;
    } catch {
        invalid();
    }
    if (rows.length !== expectedLength) invalid();
    const rgb = Buffer.alloc(width * height * 3);
    for (let y = 0; y < height; y++) {
        const row = y * (stride + 1), filter = rows[row];
        if (filter > 4) invalid();
        for (let x = 0; x < stride; x++) {
            const index = y * stride + x;
            const left = x >= 3 ? rgb[index - 3] : 0;
            const up = y ? rgb[index - stride] : 0;
            const corner = y && x >= 3 ? rgb[index - stride - 3] : 0;
            const predictor = [0, left, up, Math.floor((left + up) / 2), paeth(left, up, corner)][filter];
            rgb[index] = (rows[row + 1 + x] + predictor) & 255;
        }
    }
    return { width, height, rgb_sha256: createHash('sha256').update(rgb).digest('hex') };
}
