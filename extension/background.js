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
const ALARM_RETRY = 'sync-retry';

async function schedule() {
  const m = await engine.meta();
  const interval = INTERVALS.find((i) => i.id === m.interval) ?? INTERVALS.find((i) => i.id === 'MINUTES_15');
  await chrome.alarms.clear(ALARM_PERIODIC);
  if (!m.signedOut && interval.minutes > 0) await chrome.alarms.create(ALARM_PERIODIC, { periodInMinutes: interval.minutes, delayInMinutes: 1 });
}

/**
 * A few seconds after a change; each change restarts the wait, so a burst
 * of edits is one round. The alarm is the fallback if Chrome stops this
 * worker first (half a minute is the shortest alarm Chrome allows).
 */
let soonTimer = null;
function soon() {
  chrome.alarms.create(ALARM_SOON, { delayInMinutes: 0.5 });
  clearTimeout(soonTimer);
  soonTimer = setTimeout(() => {
    soonTimer = null;
    chrome.alarms.clear(ALARM_SOON);
    syncNow();
  }, EDIT_PAUSE_MS);
  updateBadge();
}
const EDIT_PAUSE_MS = 3000;

/**
 * One round plus its follow-up. Callers that arrive while it runs (a tap,
 * an alarm, an edit) share it, so the retry bookkeeping runs once per round.
 */
let syncing = null;
function syncNow() {
  if (!syncing) {
    syncing = (async () => {
      const out = await engine.round();
      await scheduleRetry(out);
      await updateBadge();
      return out;
    })().finally(() => { syncing = null; });
  }
  return syncing;
}

/**
 * A round that failed for a passing reason (no network, Drive busy) tries
 * again by itself: after 1, 2, 4… minutes, at most 30. Success resets it.
 */
async function scheduleRetry(out) {
  // Read and written inside the engine's queue: an edit made meanwhile
  // can't be overwritten with this older copy of the settings.
  const attempt = await engine.exclusive(async () => {
    const m = await engine.meta();
    const next = out?.ok || !out?.retry ? 0 : (m.retryAttempt ?? 0) + 1;
    if ((m.retryAttempt ?? 0) !== next) {
      m.retryAttempt = next;
      await engine.saveMeta(m);
    }
    return next;
  });
  if (attempt === 0) await chrome.alarms.clear(ALARM_RETRY);
  else await chrome.alarms.create(ALARM_RETRY, { delayInMinutes: Math.min(30, 2 ** (attempt - 1)) });
}

/**
 * The toolbar badge: "!" when sync needs the user (sign in, review), else the
 * number of saves waiting for a version to be picked, else "↑" while changes
 * wait to upload.
 */
async function updateBadge() {
  const m = await engine.meta();
  const choices = await engine.needingChoice().catch(() => 0);
  let text = '';
  let color = '#D9643A';
  if (m.needs) { text = '!'; color = '#C0392B'; }
  else if (choices > 0) { text = String(choices); color = '#B7791F'; }
  else if (m.dirty.length > 0) text = '↑';
  await chrome.action.setBadgeText({ text });
  await chrome.action.setBadgeBackgroundColor({ color });
  await chrome.action.setTitle({
    title: choices > 0 ? `PettiBox: ${choices} save${choices > 1 ? 's' : ''} edited on 2 devices need${choices > 1 ? '' : 's'} your choice` : 'PettiBox',
  });
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
  if (alarm.name === ALARM_PERIODIC || alarm.name === ALARM_SOON || alarm.name === ALARM_RETRY) syncNow();
});

// ── Pictures and files the phones shared ────────────────────────────────
// Kept in Cache Storage under a made-up URL per content hash, so each one is
// downloaded once. The popup and viewer read the same cache.

const FILE_CACHE = 'pettibox-files';
const fileKey = (sha) => `https://files.pettibox.invalid/${sha}`;
let shared = null; // { session, files, at }
// Bumped when the library is removed from this browser. A download that
// started before can't put its file back into the emptied cache.
let generation = 0;

async function sharedFiles(refresh = false) {
  if (!shared || refresh || Date.now() - shared.at > 10 * 60_000) {
    const session = await driveTransport.open();
    if (!session) return null;
    // Only from the Google account this library belongs to.
    const owner = (await engine.meta()).account;
    const account = await session.accountEmail();
    if (!owner || owner.toLowerCase() !== account.toLowerCase()) return null;
    shared = { session, files: await session.storedFiles(), at: Date.now() };
  }
  return shared;
}

/**
 * Makes sure a shared file is in the cache. [maxBytes] skips big files (list
 * previews). Runs outside the edit queue, so pictures never wait for a sync
 * round and a big file never holds up a save.
 */
async function ensureFile(sha, maxBytes) {
  const started = generation;
  const m = await engine.meta();
  const cache = await caches.open(FILE_CACHE);
  if (await cache.match(fileKey(sha))) return { ok: true };
  if (m.signedOut) return { ok: false, reason: 'signin' };
  let s = await sharedFiles();
  if (!s) return { ok: false, reason: 'signin' };
  let f = s.files[sha];
  if (!f) { s = await sharedFiles(true); f = s?.files[sha]; }
  // A phone's clean-up may have put it in Drive's trash although a save needs it.
  if (!f && s) {
    f = await s.session.restoreFile?.(sha).catch(() => null);
    if (f) s.files[sha] = f;
  }
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
  if (started !== generation || (await engine.meta()).signedOut) return { ok: false, reason: 'signin' };
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
    const signedIn = !m.signedOut && !!(await getToken(false));
    return {
      signedIn, account: m.account, deviceName: m.deviceName, interval: m.interval, intervals: INTERVALS,
      lastSyncAt: m.lastSyncAt, lastError: m.lastError, needs: m.needs, waiting: m.dirty.length > 0,
      pendingReview: m.pendingReview ?? 0,
      devices: m.devices,
    };
  },
  library: ({ trash }) => engine.library({ trash }),
  savedPage: ({ url }) => engine.savedPage(url),
  file: ({ sha, maxBytes }) => ensureFile(sha, maxBytes),
  /** The answer to "another device wants to remove N saves". */
  async review({ keep }) {
    await engine.exclusive(async () => {
      const m = await engine.meta();
      m.reviewDecision = keep ? 'keep' : 'apply';
      m.needs = null;
      m.lastError = '';
      await engine.saveMeta(m);
    });
    return syncNow();
  },
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
  needingChoice: () => engine.needingChoice(),
  sync: () => syncNow(),
  async signIn() {
    const token = await getToken(true);
    if (!token) return { ok: false };
    await engine.restart();
    await schedule();
    // Reply as soon as authentication is ready. The UI starts the first
    // sync separately; the alarm also covers the popup closing during OAuth.
    await chrome.alarms.create(ALARM_SOON, { delayInMinutes: 0.5 });
    return { ok: true };
  },
  /** [wipe]: also remove the library from this browser (a shared computer, say). */
  async signOut({ wipe }) {
    await signOut();
    shared = null;
    generation++;
    await chrome.alarms.clear(ALARM_SOON);
    await chrome.alarms.clear(ALARM_PERIODIC);
    await chrome.alarms.clear(ALARM_RETRY);
    if (wipe) {
      await chrome.storage.local.clear();
      await caches.delete(FILE_CACHE);
    }
    const m = await engine.meta();
    m.signedOut = true;
    m.knownReset = null;
    await engine.saveMeta(m);
    await updateBadge();
    return true;
  },
  async setInterval({ interval }) {
    const m = await engine.meta();
    if (!INTERVALS.some((i) => i.id === interval)) throw new Error('Unknown sync interval');
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

// These mutate metadata or clear storage, so they must wait for sync/edits too.
for (const type of ['signOut', 'setInterval', 'setName']) {
  const handler = handlers[type];
  handlers[type] = (msg) => engine.exclusive(() => handler(msg));
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  const handler = handlers[msg?.type];
  if (!handler) return false;
  Promise.resolve(handler(msg))
    .then((result) => reply({ result }))
    .catch((e) => reply({ error: String(e?.message ?? e) }));
  return true; // reply comes later
});
