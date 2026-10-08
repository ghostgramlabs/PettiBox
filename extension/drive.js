// Google Drive for the extension: the same "PettiBox Sync" folder and files as
// the Android app (DriveSyncClient.kt), under the same drive.file scope. That
// works across the app and the extension because drive.file access belongs to
// the Google Cloud project, and both OAuth clients live in the one project.

import { gzip, gunzip, md5 } from './sync/bytes.js';
import { SyncFormatError } from './sync/codec.js';

const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const FOLDER = 'PettiBox Sync';
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

async function findFolder(token) {
  const query = `name = '${FOLDER}' and mimeType = '${FOLDER_MIME}' and trashed = false`;
  const res = await call(token, `${API}/files?q=${q(query)}&orderBy=createdTime&fields=${q('files(id)')}`);
  const files = (await res.json()).files ?? [];
  return files.length ? files[0].id : null;
}

async function syncFolder(token) {
  const found = await findFolder(token);
  if (found) return found;
  await call(token, `${API}/files?fields=id`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ name: FOLDER, mimeType: FOLDER_MIME }),
  });
  // Another device may have made one at the same moment: everyone uses the oldest.
  return findFolder(token);
}

/** The SyncTransport the engine uses. */
export const driveTransport = {
  async open() {
    const token = await getToken(false);
    if (!token) return null;
    const folder = await syncFolder(token);
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
      async accountEmail() {
        const body = await (await call(token, `${API}/about?fields=${q('user(emailAddress)')}`)).json();
        return body.user?.emailAddress ?? null;
      },
    };
  },
};
