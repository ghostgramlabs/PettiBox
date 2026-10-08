// Google Drive for the extension: the same My Drive/PettiBox/Sync folder and files
// as the Android app (DriveSyncClient.kt, DriveFolders.kt), under the same drive.file scope. That
// works across the app and the extension because drive.file access belongs to
// the Google Cloud project, and both OAuth clients live in the one project.

import { gzip, gunzip, md5 } from './sync/bytes.js';
import { SyncFormatError } from './sync/codec.js';

const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const ROOT = 'PettiBox';
const SYNC = 'Sync';
const LEGACY_SYNC = 'PettiBox Sync'; // older versions kept it at the top of My Drive
const FOLDER_MIME = 'application/vnd.google-apps.folder';

export class DriveError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** An access token, or null when the user has to sign in (interactive shows Google's sheet). */
export async function getToken(interactive) {
  try {
    const result = await chrome.identity.getAuthToken({ interactive });
    return (typeof result === 'string' ? result : result?.token) || null;
  } catch {
    return null;
  }
}

export async function signOut() {
  const token = await getToken(false);
  if (!token) return;
  await chrome.identity.removeCachedAuthToken({ token });
  // Also revoke, so the next sign-in can pick a different account.
  await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, { method: 'POST' }).catch(() => {});
}

async function call(token, url, init = {}) {
  let res;
  try {
    res = await fetch(url, { ...init, headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` } });
  } catch (e) {
    throw new DriveError(0, 'network error');
  }
  if (res.status === 401) {
    // A stale cached token: drop it so the next attempt gets a fresh one.
    await chrome.identity.removeCachedAuthToken({ token }).catch(() => {});
  }
  if (!res.ok) throw new DriveError(res.status, `Drive request failed (${res.status})`);
  return res;
}

const q = (s) => encodeURIComponent(s);

/** Folders matching [query], oldest first: [{ id, parents }]. */
async function folders(token, query) {
  const out = [];
  let page = null;
  do {
    const url = `${API}/files?q=${q(query)}&orderBy=createdTime&pageSize=1000&fields=${q('nextPageToken,files(id,parents)')}` +
      (page ? `&pageToken=${q(page)}` : '');
    const body = await (await call(token, url)).json();
    for (const f of body.files ?? []) out.push({ id: f.id, parents: f.parents ?? [] });
    page = body.nextPageToken ?? null;
  } while (page);
  return out;
}

const named = (name, parent) =>
  `name = '${name}' and mimeType = '${FOLDER_MIME}' and trashed = false` + (parent ? ` and '${parent}' in parents` : '');

async function move(token, id, { to, from = [], name }) {
  const params = `addParents=${q(to)}` + (from.length ? `&removeParents=${q(from.join(','))}` : '') + '&fields=id';
  await call(token, `${API}/files/${id}?${params}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify(name ? { name } : {}),
  });
}

/** Moves everything in [from] into [into], then removes the empty [from]. */
async function mergeInto(token, from, into) {
  for (const f of await folders(token, `'${from}' in parents and trashed = false`)) await move(token, f.id, { to: into, from: f.parents });
  await call(token, `${API}/files/${from}`, { method: 'DELETE' }).catch(() => {});
}

/** The oldest of several same-named folders; the rest are folded into it. */
async function settle(token, found) {
  if (!found.length) return null;
  for (const extra of found.slice(1)) await mergeInto(token, extra.id, found[0].id);
  return found[0].id;
}

async function createFolder(token, name, parent) {
  await call(token, `${API}/files?fields=id`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ name, mimeType: FOLDER_MIME, parents: [parent] }),
  });
}

async function findOrCreate(token, name, parent) {
  const found = await settle(token, await folders(token, named(name, parent)));
  if (found) return found;
  await createFolder(token, name, parent);
  // Another device may have made one at the same moment: settle on the oldest.
  return settle(token, await folders(token, named(name, parent)));
}

/** My Drive/PettiBox/Sync, with an older top-level "PettiBox Sync" moved in. */
async function syncFolder(token) {
  const root = await findOrCreate(token, ROOT, 'root');
  const legacy = await folders(token, named(LEGACY_SYNC));
  const current = await settle(token, await folders(token, named(SYNC, root)));
  if (current) {
    for (const old of legacy) await mergeInto(token, old.id, current);
    return current;
  }
  if (legacy.length) {
    await move(token, legacy[0].id, { to: root, from: legacy[0].parents, name: SYNC });
    for (const old of legacy.slice(1)) await mergeInto(token, old.id, legacy[0].id);
    return legacy[0].id;
  }
  return findOrCreate(token, SYNC, root);
}

async function findFolder(token, name, parent) {
  const found = await folders(token, named(name, parent));
  return found.length ? found[0].id : null;
}

// The folder hardly ever changes; look it up once per worker lifetime and account.
let known = null; // { token, id }

/** The SyncTransport the engine uses. */
export const driveTransport = {
  async open() {
    const token = await getToken(false);
    if (!token) return null;
    if (known?.token !== token) known = { token, id: await syncFolder(token) };
    const folder = known.id;
    return {
      async list() {
        const out = [];
        let page = null;
        do {
          const query = `'${folder}' in parents and trashed = false and name contains 'd-'`;
          const url = `${API}/files?q=${q(query)}&pageSize=1000&fields=${q('nextPageToken,files(id,name,md5Checksum,modifiedTime)')}` +
            (page ? `&pageToken=${q(page)}` : '');
          const body = await (await call(token, url)).json();
          for (const f of body.files ?? []) {
            out.push({ id: f.id, name: f.name, md5: f.md5Checksum ?? '', modifiedAt: Date.parse(f.modifiedTime ?? '') || 0 });
          }
          page = body.nextPageToken ?? null;
        } while (page);
        return out;
      },
      async read(file) {
        const bytes = new Uint8Array(await (await call(token, `${API}/files/${file.id}?alt=media`)).arrayBuffer());
        if (file.md5 && md5(bytes) !== file.md5.toLowerCase()) throw new SyncFormatError(`${file.name} arrived damaged`);
        try {
          return await gunzip(bytes);
        } catch {
          throw new SyncFormatError(`${file.name} isn't a valid sync file`);
        }
      },
      async write(name, json, existingId) {
        const body = await gzip(json);
        if (existingId) {
          await call(token, `${UPLOAD}/files/${existingId}?uploadType=media&fields=id`, {
            method: 'PATCH', headers: { 'Content-Type': 'application/gzip' }, body,
          });
          return;
        }
        const boundary = 'pettibox-sync-' + crypto.randomUUID();
        const head = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n` +
          JSON.stringify({ name, parents: [folder] }) +
          `\r\n--${boundary}\r\nContent-Type: application/gzip\r\n\r\n`;
        const tail = `\r\n--${boundary}--\r\n`;
        await call(token, `${UPLOAD}/files?uploadType=multipart&fields=id`, {
          method: 'POST',
          headers: { 'Content-Type': `multipart/related; boundary=${boundary}` },
          body: new Blob([head, body, tail]),
        });
      },
      /** Photos, PDFs and pictures the phones shared: sha -> { id, size, mime }. */
      async storedFiles() {
        const files = await findFolder(token, 'files', folder);
        const out = {};
        if (!files) return out;
        let page = null;
        do {
          const query = `'${files}' in parents and trashed = false`;
          const url = `${API}/files?q=${q(query)}&pageSize=1000&fields=${q('nextPageToken,files(id,name,size,mimeType)')}` +
            (page ? `&pageToken=${q(page)}` : '');
          const body = await (await call(token, url)).json();
          for (const f of body.files ?? []) {
            out[f.name.split('.')[0]] = { id: f.id, size: Number(f.size ?? 0), mime: f.mimeType ?? 'application/octet-stream' };
          }
          page = body.nextPageToken ?? null;
        } while (page);
        return out;
      },
      async readFile(id) {
        return (await call(token, `${API}/files/${id}?alt=media`)).blob();
      },
      async resetMarker() {
        const query = `'${folder}' in parents and trashed = false and name = 'pettibox-sync-reset.json'`;
        const body = await (await call(token, `${API}/files?q=${q(query)}&fields=${q('files(id)')}`)).json();
        const f = (body.files ?? [])[0];
        return f ? (await call(token, `${API}/files/${f.id}?alt=media`)).text() : null;
      },
      async accountEmail() {
        const body = await (await call(token, `${API}/about?fields=${q('user(emailAddress)')}`)).json();
        return body.user?.emailAddress ?? null;
      },
    };
  },
};
