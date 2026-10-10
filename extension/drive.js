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
  } catch (e) {
    if (interactive) throw new Error(`Google sign-in failed: ${e?.message ?? e}`);
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

/** No answer within this long counts as a network failure (bigger for file transfers). */
const TIMEOUT_MS = 30_000;
const TRANSFER_TIMEOUT_MS = 120_000;

/**
 * One Drive request. The time limit covers the whole answer, body included:
 * the reply's json(), text(), arrayBuffer() and blob() still run under it, so
 * a reply that stalls half-way fails (and is retried) instead of holding the
 * sync queue. A body nobody reads is released when the limit passes.
 */
async function call(token, url, init = {}) {
  const transfer = url.includes('alt=media') || url.startsWith(UPLOAD);
  const controller = new AbortController();
  let timedOut = false;
  let onTimeout = () => {};
  const timer = setTimeout(() => { timedOut = true; controller.abort(); onTimeout(); }, transfer ? TRANSFER_TIMEOUT_MS : TIMEOUT_MS);
  timer.unref?.(); // where timers can keep a process open (tests), a reply nobody reads must not
  const expired = new Promise((_, reject) => {
    onTimeout = () => reject(new DriveError(0, 'Google Drive took too long to answer'));
  });
  expired.catch(() => {});
  let res;
  try {
    res = await Promise.race([fetch(url, {
      ...init,
      signal: controller.signal,
      headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` },
    }), expired]);
  } catch (e) {
    clearTimeout(timer);
    if (e instanceof DriveError) throw e;
    throw new DriveError(0, timedOut || controller.signal.aborted ? 'Google Drive took too long to answer' : 'network error');
  }
  if (res.status === 401) {
    // A stale cached token: drop it so the next attempt gets a fresh one.
    await chrome.identity.removeCachedAuthToken({ token }).catch(() => {});
  }
  if (!res.ok) {
    clearTimeout(timer);
    throw new DriveError(res.status, `Drive request failed (${res.status})`);
  }
  const body = (read) => async () => {
    try {
      return await Promise.race([read(), expired]);
    } catch (e) {
      if (e instanceof DriveError) throw e;
      throw new DriveError(0, timedOut ? 'Google Drive took too long to answer' : 'network error');
    } finally {
      clearTimeout(timer);
    }
  };
  return {
    ok: true,
    status: res.status,
    headers: res.headers,
    json: body(() => res.json()),
    text: body(() => res.text()),
    arrayBuffer: body(() => res.arrayBuffer()),
    blob: body(() => res.blob()),
  };
}

const q = (s) => encodeURIComponent(s);

/** Files and folders matching [query], oldest first: [{ id, parents, name, folder }]. */
async function folders(token, query) {
  const out = [];
  let page = null;
  do {
    const url = `${API}/files?q=${q(query)}&orderBy=createdTime&pageSize=1000&fields=${q('nextPageToken,files(id,parents,name,mimeType,explicitlyTrashed,appProperties)')}` +
      (page ? `&pageToken=${q(page)}` : '');
    const body = await (await call(token, url)).json();
    for (const f of body.files ?? []) {
      out.push({
        id: f.id, parents: f.parents ?? [], name: f.name, folder: f.mimeType === FOLDER_MIME,
        explicitlyTrashed: f.explicitlyTrashed === true, mergedInto: f.appProperties?.pettiboxMergedInto ?? null,
      });
    }
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

/**
 * Moves everything in [from] into [into]; a sub-folder [into] already has
 * (two "files" folders, say) is merged the same way rather than moved
 * alongside. [from] is never deleted: once a fresh look finds it empty it
 * goes to Drive's trash, so anything a device put there at that very moment
 * can still be recovered, and devices stop using it (see openFolder).
 */
async function mergeInto(token, from, into) {
  const existing = await folders(token, `'${into}' in parents and trashed = false and mimeType = '${FOLDER_MIME}'`);
  for (const f of await folders(token, `'${from}' in parents and trashed = false`)) {
    const same = f.folder ? existing.find((e) => e.name === f.name) : null;
    if (same) await mergeInto(token, f.id, same.id);
    else await move(token, f.id, { to: into, from: f.parents });
  }
  if ((await folders(token, `'${from}' in parents and trashed = false`)).length) return; // more arrived: next time
  // Labelled, so anything a device puts in it from now on is moved out
  // again (see drain) rather than lost with it.
  await call(token, `${API}/files/${from}?fields=id`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ trashed: true, appProperties: { pettiboxMergedInto: into } }),
  }).catch(() => {});
}

/**
 * Brings back into [into] anything put in a folder PettiBox merged into it
 * after it was trashed (a device still using the old folder at that moment).
 * Only folders PettiBox itself retired are drained, never ones a person trashed.
 */
async function drain(token, into, name, parent) {
  const retired = await folders(token, `name = '${name}' and mimeType = '${FOLDER_MIME}' and trashed = true and '${parent}' in parents`);
  for (const r of retired.filter((x) => x.mergedInto === into)) {
    const existing = await folders(token, `'${into}' in parents and trashed = false and mimeType = '${FOLDER_MIME}'`);
    for (const c of await folders(token, `'${r.id}' in parents`)) {
      if (c.explicitlyTrashed) continue;
      const same = c.folder ? existing.find((x) => x.name === c.name) : null;
      if (same) await drain(token, same.id, c.name, r.id).then(() => mergeInto(token, c.id, same.id));
      else await move(token, c.id, { to: into, from: c.parents });
    }
  }
}

/** The oldest of several same-named folders; the rest are folded into it (and late arrivals drained back). */
async function settle(token, found, name, parent) {
  if (!found.length) return null;
  for (const extra of found.slice(1)) await mergeInto(token, extra.id, found[0].id);
  if (name) await drain(token, found[0].id, name, parent);
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
  const found = await settle(token, await folders(token, named(name, parent)), name, parent);
  if (found) return found;
  await createFolder(token, name, parent);
  // Another device may have made one at the same moment: settle on the oldest.
  return settle(token, await folders(token, named(name, parent)), name, parent);
}

/** My Drive/PettiBox/Sync, with an older top-level "PettiBox Sync" moved in. */
async function syncFolder(token) {
  const root = await findOrCreate(token, ROOT, 'root');
  const legacy = await folders(token, named(LEGACY_SYNC));
  const current = await settle(token, await folders(token, named(SYNC, root)), SYNC, root);
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

// The folder hardly ever changes; look it up once per worker lifetime and
// account, and check it's still in use (not trashed by a merge) each round.
let known = null; // { token, id }

async function stillInUse(token, id) {
  try {
    const body = await (await call(token, `${API}/files/${id}?fields=${q('trashed')}`)).json();
    return body.trashed !== true;
  } catch (e) {
    if (e?.status === 404) return false;
    throw e;
  }
}

/** The SyncTransport the engine uses. */
export const driveTransport = {
  async open() {
    const token = await getToken(false);
    if (!token) return null;
    // Found (and duplicates merged) only when first needed, after the
    // engine has checked whose Drive this is.
    let checked = false;
    const folder = async () => {
      if (!checked) {
        if (known?.token !== token || !(await stillInUse(token, known.id))) known = { token, id: await syncFolder(token) };
        checked = true;
      }
      return known.id;
    };
    return {
      async list() {
        const out = [];
        let page = null;
        do {
          const query = `'${await folder()}' in parents and trashed = false and name contains 'd-'`;
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
          JSON.stringify({ name, parents: [await folder()] }) +
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
        // Two devices may each have made a files folder: fold them into one.
        const files = await settle(token, await folders(token, named('files', await folder())), 'files', await folder());
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
      /**
       * A shared file a phone's clean-up put in Drive's trash although a save
       * needs it: taken back out into the files folder. Null if there's none.
       */
      async restoreFile(sha) {
        const lookup = async (query) => {
          const body = await (await call(token, `${API}/files?q=${q(query)}&fields=${q('files(id,name,parents,size,mimeType)')}`)).json();
          return (body.files ?? []).find((x) => x.name.split('.')[0] === sha) ?? null;
        };
        // In files-removed, waiting out its last 30 days; or in Drive's trash (older phones).
        const removed = await findFolder(token, 'files-removed', await folder());
        const f = (removed && await lookup(`name contains '${sha}' and trashed = false and '${removed}' in parents`))
          ?? await lookup(`name contains '${sha}' and trashed = true`);
        if (!f) return null;
        const files = await settle(token, await folders(token, named('files', await folder())), 'files', await folder())
          ?? (await createFolder(token, 'files', await folder()), await findFolder(token, 'files', await folder()));
        const params = `addParents=${q(files)}` + ((f.parents ?? []).length ? `&removeParents=${q(f.parents.join(','))}` : '') + '&fields=id';
        await call(token, `${API}/files/${f.id}?${params}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json; charset=UTF-8' },
          body: JSON.stringify({ trashed: false }),
        });
        return { id: f.id, size: Number(f.size ?? 0), mime: f.mimeType ?? 'application/octet-stream' };
      },
      async readFile(id) {
        return (await call(token, `${API}/files/${id}?alt=media`)).blob();
      },
      /**
       * The reset marker's content (null if sync was never reset). Two
       * devices resetting at once can leave two markers: every device takes
       * the smallest content, so all agree on the same one.
       */
      async resetMarker() {
        const query = `'${await folder()}' in parents and trashed = false and name = 'pettibox-sync-reset.json'`;
        const body = await (await call(token, `${API}/files?q=${q(query)}&fields=${q('files(id)')}`)).json();
        const found = body.files ?? [];
        if (!found.length) return null;
        const texts = [];
        for (const f of found) texts.push(await (await call(token, `${API}/files/${f.id}?alt=media`)).text());
        return texts.sort()[0];
      },
      /** The signed-in account's email; throws if Drive can't say (sync then waits). */
      async accountEmail() {
        const body = await (await call(token, `${API}/about?fields=${q('user(emailAddress)')}`)).json();
        if (!body.user?.emailAddress) throw new DriveError(503, 'Google Drive didn\'t say which account this is');
        return body.user.emailAddress;
      },
    };
  },
};
