// Service worker: owns the sync engine. The popup and options page talk to it
// by message, so every change goes through one queue. Chrome stops this
// worker whenever it's idle; everything lasting is in chrome.storage.local.

import { SyncEngine, INTERVALS } from './sync/engine.js';
import { shardOf } from './sync/model.js';
import { driveTransport, getToken, signOut } from './drive.js';

const store = {
  get: (keys) => chrome.storage.local.get(keys),
  set: (items) => chrome.storage.local.set(items),
};
const engine = new SyncEngine(store, driveTransport);

const ALARM_PERIODIC = 'sync-periodic';
const ALARM_SOON = 'sync-soon';

async function schedule() {
  const m = await engine.meta();
  const interval = INTERVALS.find((i) => i.id === m.interval) ?? INTERVALS.find((i) => i.id === 'HOURS_3');
  await chrome.alarms.clear(ALARM_PERIODIC);
  if (interval.minutes > 0) chrome.alarms.create(ALARM_PERIODIC, { periodInMinutes: interval.minutes, delayInMinutes: 1 });
}

/** About half a minute after a change (Chrome's shortest alarm); each change pushes it back. */
function soon() {
  chrome.alarms.create(ALARM_SOON, { delayInMinutes: 0.5 });
  updateBadge();
}

async function syncNow() {
  const out = await engine.round();
  await updateBadge();
  return out;
}

async function updateBadge() {
  const m = await engine.meta();
  const waiting = m.dirty.length > 0;
  const problem = !!m.needs;
  await chrome.action.setBadgeText({ text: problem ? '!' : waiting ? '↑' : '' });
  await chrome.action.setBadgeBackgroundColor({ color: problem ? '#C0392B' : '#D9643A' });
}

chrome.runtime.onInstalled.addListener(async () => {
  chrome.contextMenus.create({ id: 'save-page', title: 'Save page to PettiBox', contexts: ['page'] });
  chrome.contextMenus.create({ id: 'save-link', title: 'Save link to PettiBox', contexts: ['link'] });
  await engine.meta();
  await schedule();
});

chrome.runtime.onStartup.addListener(async () => {
  await schedule();
  if (await getToken(false)) syncNow();
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_PERIODIC || alarm.name === ALARM_SOON) syncNow();
});

// ── Pictures and files the phones shared ────────────────────────────────
// Kept in Cache Storage under a made-up URL per content hash, so each one is
// downloaded once. The popup and viewer read the same cache.

const FILE_CACHE = 'pettibox-files';
const fileKey = (sha) => `https://files.pettibox.invalid/${sha}`;
let shared = null; // { session, files, at }

async function sharedFiles(refresh = false) {
  if (!shared || refresh || Date.now() - shared.at > 10 * 60_000) {
    const session = await driveTransport.open();
    if (!session) return null;
    shared = { session, files: await session.storedFiles(), at: Date.now() };
  }
  return shared;
}

/** Makes sure a shared file is in the cache. [maxBytes] skips big files (list previews). */
async function ensureFile(sha, maxBytes) {
  const cache = await caches.open(FILE_CACHE);
  if (await cache.match(fileKey(sha))) return { ok: true };
  let s = await sharedFiles();
  if (!s) return { ok: false, reason: 'signin' };
  let f = s.files[sha];
  if (!f) { s = await sharedFiles(true); f = s?.files[sha]; }
  // Not uploaded yet: phones send full files on Wi-Fi by default.
  if (!f) return { ok: false, reason: 'missing' };
  if (maxBytes && f.size > maxBytes) return { ok: false, reason: 'large' };
  let blob;
  try {
    blob = await s.session.readFile(f.id);
  } catch (e) {
    shared = null; // a stale token or folder: start fresh next time
    throw e;
  }
  const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))]
    .map((b) => b.toString(16).padStart(2, '0')).join('');
  if (digest !== sha) return { ok: false, reason: 'damaged' };
  await cache.put(fileKey(sha), new Response(blob, { headers: { 'Content-Type': f.mime } }));
  return { ok: true };
}

// ── Saving ───────────────────────────────────────────────────────────────

async function saveTab(tab) {
  if (!tab?.url || !/^https?:/i.test(tab.url)) return null;
  const result = await engine.saveLink({ url: tab.url, title: tab.title || tab.url });
  soon();
  await chrome.action.setBadgeText({ text: result.existed ? '=' : '✓', tabId: tab.id });
  setTimeout(() => chrome.action.setBadgeText({ text: '', tabId: tab.id }), 2500);
  return result;
}

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== 'save-page') return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  await saveTab(tab);
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.menuItemId === 'save-link' ? info.linkUrl : (info.pageUrl ?? tab?.url);
  if (!url || !/^https?:/i.test(url)) return;
  const title = info.menuItemId === 'save-link' ? (info.selectionText || url) : (tab?.title || url);
  const result = await engine.saveLink({ url, title });
  soon();
  await chrome.action.setBadgeText({ text: result.existed ? '=' : '✓', tabId: tab?.id });
  setTimeout(() => chrome.action.setBadgeText({ text: '', tabId: tab?.id }), 2500);
});

// Popup and options requests. Each returns a promise; errors come back as { error }.
const handlers = {
  async state() {
    const m = await engine.meta();
    const signedIn = !!(await getToken(false));
    return {
      signedIn, account: m.account, deviceName: m.deviceName, interval: m.interval, intervals: INTERVALS,
      lastSyncAt: m.lastSyncAt, lastError: m.lastError, needs: m.needs, waiting: m.dirty.length > 0,
      devices: m.devices,
    };
  },
  library: ({ trash }) => engine.library({ trash }),
  savedPage: ({ url }) => engine.savedPage(url),
  file: ({ sha, maxBytes }) => ensureFile(sha, maxBytes),
  async save({ colName, cat = null, ...args }) {
    if (!cat && colName) cat = await engine.collectionNamed(colName);
    const r = await engine.saveLink({ ...args, cat });
    soon();
    return r;
  },
  async update({ id, changes, colName }) {
    if (colName !== undefined) changes.cat = colName ? await engine.collectionNamed(colName) : null;
    await engine.updateItem(id, changes);
    soon();
    return true;
  },
  async trash({ id }) { await engine.trash(id); soon(); return true; },
  async restore({ id }) { await engine.restore(id); soon(); return true; },
  async keepShown({ id, field }) { await engine.keepShown(id, field); soon(); return true; },
  sync: () => syncNow(),
  async signIn() {
    const token = await getToken(true);
    if (!token) return { ok: false };
    await schedule();
    const out = await syncNow();
    return { ok: true, sync: out };
  },
  async signOut() { await signOut(); await updateBadge(); return true; },
  async setInterval({ interval }) {
    const m = await engine.meta();
    m.interval = interval;
    await engine.saveMeta(m);
    await schedule();
    return true;
  },
  async setName({ name }) {
    const m = await engine.meta();
    m.deviceName = String(name).trim().slice(0, 40) || 'Chrome';
    // Republish the files that hold records, so other devices see the new name.
    const held = new Set([...(await engine.records()).values()].map((r) => shardOf(r.id)));
    m.dirty = [...new Set([...m.dirty, ...(held.size ? held : [0])])];
    await engine.saveMeta(m);
    soon();
    return true;
  },
};

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  const handler = handlers[msg?.type];
  if (!handler) return false;
  Promise.resolve(handler(msg))
    .then((result) => reply({ result }))
    .catch((e) => reply({ error: String(e?.message ?? e) }));
  return true; // reply comes later
});
