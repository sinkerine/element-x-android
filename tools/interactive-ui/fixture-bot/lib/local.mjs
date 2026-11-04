// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
import { mkdir, writeFile, readFile, stat, chmod } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve, join } from 'node:path';
import { createHmac, randomBytes } from 'node:crypto';
export const baseUrl = 'http://127.0.0.1:18028';
export const controlUrl = 'http://127.0.0.1:18029';
export const localDir = join(homedir(), '.local/share/element-x-e2e/interactive-ui');
export const credentialsPath = join(localDir, 'credentials.json');
export const tokenPath = join(localDir, 'control-token');
export const sandboxDir = resolve('build/e2e/interactive-ui/synapse');
export async function secureWrite(path, value) {
    await mkdir(localDir, { recursive: true, mode: 0o700 });
    await chmod(localDir, 0o700);
    await writeFile(path, value, { mode: 0o600 });
    await chmod(path, 0o600);
}
export async function secureRead(path) {
    const info = await stat(path);
    if ((info.mode & 0o077) !== 0 || info.uid !== process.getuid()) throw new Error('Local secret must be owner-only');
    return readFile(path, 'utf8');
}
export async function request(path, body, token) {
    const response = await fetch(`${baseUrl}${path}`, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(`Homeserver HTTP ${response.status} ${data.errcode ?? ''}`);
    return data;
}
export async function provision() {
    // A rerun uses the same dedicated accounts; never overwrite existing passwords.
    let accounts = {};
    try {
        accounts = JSON.parse(await secureRead(credentialsPath));
        if (accounts.alice && accounts.uibot) return { credentials: 'existing owner-only file' };
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const yaml = await readFile(join(sandboxDir, 'homeserver.yaml'), 'utf8');
    const secret = yaml.match(/^registration_shared_secret:\s*["']?([^\s"']+)/m)?.[1];
    if (!secret) throw new Error('Missing registration secret');
    for (const username of ['alice', 'uibot']) {
        if (accounts[username]) continue;
        const password = randomBytes(24).toString('base64url');
        const { nonce } = await request('/_synapse/admin/v1/register');
        const mac = createHmac('sha1', secret).update(`${nonce}\0${username}\0${password}\0notadmin`).digest('hex');
        const data = await request('/_synapse/admin/v1/register', { nonce, username, password, admin: false, mac,
            displayname: username === 'uibot' ? 'Fixture sender' : 'Alice' });
        accounts[username] = { userId: data.user_id, password };
        // Persist each successful registration so an interrupted provision never loses that password.
        await secureWrite(credentialsPath, JSON.stringify(accounts, null, 2));
    }
    return { credentials: 'created owner-only file' };
}
