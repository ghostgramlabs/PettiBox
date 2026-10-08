// The extension's side of "Sync between devices". The browser is just another
// device: it keeps its own merged copy of the library, merges in every other
// device's files from Drive, and publishes its own 16 files. Nothing is ever
// deleted; a delete here moves a save to Recently deleted, as on the phone.
//
// Storage and Drive come in as interfaces so the same code runs in Chrome
// (chrome.storage.local, Google Drive) and in tests (memory, a fake Drive).
//   store:     { get(keys: string[]) -> Promise<object>, set(items: object) -> Promise<void> }
//   transport: { open() -> Promise<Session|null> }
//   Session:   { list(), read(file), write(name, json, existingId), accountEmail() }

import {
  F, KIND_COLLECTION, KIND_ITEM, HybridClock, merge, diff, value, trashedAt, isGone, isTrashed,
  presentTags, presentFiles, maxTs, shardOf, alternatives, Write,
} from './model.js';
import { encodeRecord, decodeRecord, encodeShard, decodeShard, SyncFormatError } from './codec.js';

export const INTERVALS = [
  { id: 'MINUTES_15', label: '15 min', minutes: 15 },
  { id: 'MINUTES_30', label: '30 min', minutes: 30 },
  { id: 'HOUR_1', label: '1 hour', minutes: 60 },
  { id: 'HOURS_3', label: '3 hours', minutes: 180 },
  { id: 'DAILY', label: 'Daily', minutes: 1440 },
  { id: 'WEEKLY', label: 'Weekly', minutes: 10080 },
  { id: 'MANUAL', label: 'Manual only', minutes: 0 },
];

const META = 'meta';
const shardKey = (n) => 'shard:' + n;
const recKey = (kind, id) => kind + ':' + id;
const SHARD_KEYS = Array.from({ length: 16 }, (_, n) => shardKey(n));

export function shardName(device, shard) {
  return `d-${device}-${shard.toString(16)}.json.gz`;
}

export function deviceOf(name) {
  const m = /^d-([0-9a-f]{8,32})-([0-9a-f])\.json\.gz$/.exec(name);
  return m ? m[1] : null;
}

/** Same rule as TextUtils.urlDedupeKey: scheme and host are case-insensitive, the rest isn't. */
export function urlKey(url) {
  const t = url.trim();
  const schemeEnd = t.indexOf('://');
  if (schemeEnd <= 0) return t;
  const start = schemeEnd + 3;
  let end = t.length;
  for (const ch of ['/', '?', '#']) {
    const i = t.indexOf(ch, start);
    if (i >= 0 && i < end) end = i;
  }
  return t.slice(0, end).toLowerCase() + t.slice(end);
}

function randomHex(bytes) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export class SyncEngine {
  constructor(store, transport, now = () => Date.now()) {
    this.store = store;
    this.transport = transport;
    this.now = now;
    this.running = null;
    this.lock = Promise.resolve();
  }

  /**
   * Runs [fn] after every earlier change has finished. Edits from the popup
   * and sync rounds share the one queue, so a round can never write back a
   * stale copy over an edit made while it ran.
   */
  exclusive(fn) {
    const run = this.lock.then(fn, fn);
    this.lock = run.catch(() => {});
    return run;
  }

  // ── State ──────────────────────────────────────────────────────────────

  async meta() {
    const got = await this.store.get([META]);
    const m = got[META] ?? {};
    let changed = false;
    if (!m.deviceId) { m.deviceId = randomHex(8); changed = true; }
    if (!m.deviceName) { m.deviceName = 'Chrome'; changed = true; }
    m.clock ??= 0;
    m.interval ??= 'HOURS_3';
    m.seenMd5 ??= {};
    m.devices ??= {};
    m.dirty ??= [];
    m.lastSyncAt ??= 0;
    m.lastError ??= '';
    m.account ??= '';
    if (changed) await this.store.set({ [META]: m });
    return m;
  }

  async saveMeta(m) {
    await this.store.set({ [META]: m });
  }

  /**
   * Starting sync (again) in this browser: forget what was read, note Drive
   * afresh, and re-upload this browser's whole copy, since Drive may have
   * been emptied (a reset, or this browser's files removed).
   */
  restart() {
    return this.exclusive(async () => {
      const m = await this.meta();
      const held = new Set([...(await this.records()).values()].map((r) => shardOf(r.id)));
      m.stopped = false;
      m.knownReset = null;
      m.seenMd5 = {};
      m.dirty = [...held];
      m.lastError = '';
      m.needs = null;
      await this.saveMeta(m);
    });
  }

  /** Every record this browser holds: Map "kind:id" -> record. */
  async records() {
    const got = await this.store.get(SHARD_KEYS);
    const out = new Map();
    for (const key of SHARD_KEYS) {
      for (const [k, o] of Object.entries(got[key] ?? {})) {
        try { out.set(k, decodeRecord(o)); } catch { /* skip a damaged entry */ }
      }
    }
    return out;
  }

  /** Writes records back, one storage key per shard touched. Returns the shards. */
  async putRecords(list) {
    const byShard = new Map();
    for (const r of list) {
      const n = shardOf(r.id);
      if (!byShard.has(n)) byShard.set(n, []);
      byShard.get(n).push(r);
    }
    const keys = [...byShard.keys()].map(shardKey);
    const got = await this.store.get(keys);
    const out = {};
    for (const [n, recs] of byShard) {
      const shard = { ...(got[shardKey(n)] ?? {}) };
      for (const r of recs) shard[recKey(r.kind, r.id)] = encodeRecord(r);
      out[shardKey(n)] = shard;
    }
    await this.store.set(out);
    return [...byShard.keys()];
  }

  // ── The library as the popup shows it ──────────────────────────────────

  /**
   * Saves and collections to show: live saves (or Recently deleted with
   * [trash]), never any in a locked collection or a locked collection
   * itself. The browser has no way to unlock, so those stay out.
   */
  async library({ trash = false } = {}) {
    const recs = await this.records();
    const collections = [];
    const locked = new Set();
    for (const r of recs.values()) {
      if (r.kind !== KIND_COLLECTION || isGone(r)) continue;
      if (value(r, F.LOCKED) === true) { locked.add(r.id); continue; }
      collections.push({
        id: r.id, name: value(r, F.NAME) ?? 'Collection', emoji: value(r, F.EMOJI) ?? '📦',
        color: value(r, F.COLOR) ?? 0xff8b5cf6, order: value(r, F.ORDER) ?? 0,
      });
    }
    collections.sort((a, b) => a.name.localeCompare(b.name));
    const known = new Set(collections.map((c) => c.id));
    const items = [];
    for (const r of recs.values()) {
      if (r.kind !== KIND_ITEM || isGone(r)) continue;
      if (isTrashed(r) !== trash) continue;
      const cat = value(r, F.CAT);
      if (cat && locked.has(cat)) continue;
      items.push(SyncEngine.itemView(r, known));
    }
    items.sort((a, b) => (b.pin - a.pin) || (b.created - a.created));
    return { items, collections };
  }

  /** One save as the popup shows it. [known]: collection ids that may be shown. */
  static itemView(r, known) {
    const cat = value(r, F.CAT);
    const files = Object.entries(presentFiles(r))
      .sort(([, a], [, b]) => a.order - b.order)
      .map(([sha, f]) => ({ sha, kind: f.kind }));
    const main = value(r, F.MAIN);
    return {
      id: r.id,
      title: value(r, F.TITLE) ?? 'Untitled',
      url: value(r, F.URL),
      notes: value(r, F.NOTES),
      ocr: value(r, F.OCR),
      cat: cat && known.has(cat) ? cat : null,
      type: value(r, F.TYPE),
      fav: value(r, F.FAV) === true,
      pin: value(r, F.PIN) === true,
      arch: value(r, F.ARCH) === true,
      tags: Object.values(presentTags(r)),
      created: r.created,
      updated: maxTs(r),
      trashed: isTrashed(r),
      trashedAt: trashedAt(r),
      // Pictures and files live in Drive by content hash; the popup fetches them on demand.
      thumb: value(r, F.THUMB),
      main,
      files: main ? [{ sha: main, kind: value(r, F.TYPE) ?? 'FILE' }, ...files] : files,
      conflicts: [F.TITLE, F.NOTES, F.URL]
        .map((f) => ({ field: f, shown: value(r, f), others: alternatives(r, f).map((h) => h.value) }))
        .filter((c) => c.others.length > 0),
    };
  }

  /** The save holding this link, as the popup shows it (null if none, or it's in a locked collection). */
  async savedPage(url) {
    const r = await this.findByUrl(url);
    if (!r) return null;
    const lib = await this.library({ trash: isTrashed(r) });
    return lib.items.find((i) => i.id === r.id) ?? null;
  }

  // ── Edits made in the browser ──────────────────────────────────────────

  /** What this browser shows for a record, as plain values (input for diff). */
  static localOf(r) {
    const fields = {};
    for (const name of Object.keys(r.fields)) fields[name] = name === F.TRASH ? trashedAt(r) : value(r, name);
    if (!(F.TRASH in fields)) fields[F.TRASH] = null;
    const files = {};
    for (const [sha, f] of Object.entries(presentFiles(r))) files[sha] = { kind: f.kind, order: f.order };
    return { kind: r.kind, id: r.id, created: r.created, fields, tags: presentTags(r), files };
  }

  /** Applies one local edit to a record (creating it if [create]). Returns the new record. */
  edit(kind, id, change, create = null) {
    return this.exclusive(() => this.editNow(kind, id, change, create));
  }

  async editNow(kind, id, change, create) {
    const m = await this.meta();
    const clock = new HybridClock(m.clock, this.now);
    const recs = await this.records();
    const current = recs.get(recKey(kind, id)) ?? null;
    let local = current ? SyncEngine.localOf(current) : create;
    if (!local) throw new Error('no such save');
    local = change(structuredClone(local));
    const next = diff(current, local, { dev: m.deviceId, ts: clock.next() });
    if (!next) return current;
    const shards = await this.putRecords([next]);
    m.clock = clock.last;
    m.dirty = [...new Set([...m.dirty, ...shards])];
    await this.saveMeta(m);
    return next;
  }

  /** The save already holding this link, if any (live or in Recently deleted). */
  async findByUrl(url) {
    const k = urlKey(url);
    for (const r of (await this.records()).values()) {
      if (r.kind !== KIND_ITEM || isGone(r)) continue;
      const u = value(r, F.URL);
      if (u && urlKey(u) === k) return r;
    }
    return null;
  }

  /** Saves a link, or returns the save that already has it. Queued whole, so a double click saves once. */
  saveLink(args) {
    return this.exclusive(() => this.saveLinkNow(args));
  }

  async saveLinkNow({ url, title, cat = null, tags = [], notes = null }) {
    const existing = await this.findByUrl(url);
    if (existing && !isTrashed(existing)) return { id: existing.id, existed: true };
    if (existing) {
      // Saving a link that's in Recently deleted brings that save back.
      await this.editNow(KIND_ITEM, existing.id, (l) => { l.fields[F.TRASH] = null; return l; }, null);
      return { id: existing.id, existed: true };
    }
    const id = randomHex(16);
    const now = this.now();
    const tagMap = {};
    for (const t of tags.map((x) => x.trim()).filter(Boolean)) tagMap[t.toLowerCase()] ??= t;
    await this.editNow(KIND_ITEM, id, (l) => l, {
      kind: KIND_ITEM, id, created: now,
      fields: {
        [F.TITLE]: title?.trim() || url, [F.URL]: url, [F.NOTES]: notes?.trim() || null, [F.OCR]: null,
        [F.META]: null, [F.CAT]: cat, [F.TYPE]: 'LINK', [F.SRC]: 'CHROME', [F.FAV]: false, [F.PIN]: false,
        [F.ARCH]: false, [F.REMIND]: null, [F.OPENED]: null, [F.TRASH]: null, [F.MAIN]: null,
      },
      tags: tagMap, files: {},
    });
    return { id, existed: false };
  }

  /**
   * The collection with this name (any case), made if there's none. Made
   * here, it's a collection like any other: every device gets it.
   */
  collectionNamed(name) {
    return this.exclusive(async () => {
      const wanted = name.trim();
      if (!wanted) return null;
      const recs = await this.records();
      let order = 0;
      for (const r of recs.values()) {
        if (r.kind !== KIND_COLLECTION || isGone(r)) continue;
        order = Math.max(order, (value(r, F.ORDER) ?? 0) + 1);
        if (value(r, F.LOCKED) !== true && (value(r, F.NAME) ?? '').toLowerCase() === wanted.toLowerCase()) return r.id;
      }
      const id = 'user_' + randomHex(4);
      await this.editNow(KIND_COLLECTION, id, (l) => l, {
        kind: KIND_COLLECTION, id, created: this.now(),
        fields: {
          [F.NAME]: wanted.slice(0, 28), [F.EMOJI]: '📦', [F.COLOR]: 0xff8b5cf6, [F.ORDER]: order,
          [F.PARENT]: null, [F.USER]: true, [F.LOCKED]: false,
        },
        tags: {}, files: {},
      });
      return id;
    });
  }

  /** Changes to a save: any of title, url, notes, cat, fav, pin, arch, tags (array of names). */
  async updateItem(id, changes) {
    return this.edit(KIND_ITEM, id, (l) => {
      for (const f of ['title', 'url', 'notes', 'cat', 'fav', 'pin', 'arch']) {
        if (f in changes) l.fields[f] = changes[f] === '' && f !== 'title' ? null : changes[f];
      }
      if (changes.tags) {
        l.tags = {};
        for (const t of changes.tags.map((x) => x.trim()).filter(Boolean)) l.tags[t.toLowerCase()] ??= t;
      }
      return l;
    });
  }

  async trash(id) {
    const at = this.now();
    return this.edit(KIND_ITEM, id, (l) => { l.fields[F.TRASH] = at; return l; });
  }

  async restore(id) {
    return this.edit(KIND_ITEM, id, (l) => { l.fields[F.TRASH] = null; return l; });
  }

  /** "Edited on 2 devices": keep what's shown (other versions dropped everywhere). */
  keepShown(id, field) {
    return this.exclusive(() => this.keepShownNow(id, field));
  }

  async keepShownNow(id, field) {
    const m = await this.meta();
    const clock = new HybridClock(m.clock, this.now);
    const r = (await this.records()).get(recKey(KIND_ITEM, id));
    if (!r || alternatives(r, field).length === 0) return;
    const next = Write.field(r, field, value(r, field), { dev: m.deviceId, ts: clock.next() });
    const shards = await this.putRecords([next]);
    m.clock = clock.last;
    m.dirty = [...new Set([...m.dirty, ...shards])];
    await this.saveMeta(m);
  }

  // ── A sync round ───────────────────────────────────────────────────────

  /** One round at a time; a second call joins the running one. */
  round() {
    if (!this.running) this.running = this.exclusive(() => this.roundOnce()).finally(() => { this.running = null; });
    return this.running;
  }

  async roundOnce() {
    const m = await this.meta();
    let session;
    try {
      session = await this.transport.open();
    } catch (e) {
      return this.fail(m, 'Couldn\'t reach Google Drive', true);
    }
    if (!session) return this.fail(m, 'Sign in to Google to sync', false, 'signin');
    if (m.stopped) return this.fail(m, m.lastError || 'Sync is off. Sign in again to start over.', false, 'reset');
    try {
      // Synced data deleted from Drive since this browser signed in? Stop
      // rather than upload everything again; what's here stays.
      const marker = (await session.resetMarker?.()) ?? '';
      if (m.knownReset == null) {
        m.knownReset = marker;
      } else if (marker !== m.knownReset) {
        m.stopped = true;
        return this.fail(m, 'Sync was turned off because the synced data was deleted from Google Drive. Your library is still here; sign in again to start over.', false, 'reset');
      }
      const clock = new HybridClock(m.clock, this.now);
      const now = this.now();
      const files = await session.list();
      const ours = {};
      const received = new Map();
      const newSeen = { ...m.seenMd5 };
      let damaged = 0;
      for (const f of files) {
        const dev = deviceOf(f.name);
        if (!dev) continue;
        if (dev === m.deviceId) { ours[f.name] = f.id; continue; }
        if (f.md5 && m.seenMd5[f.name] === f.md5) continue;
        let shard;
        try {
          shard = decodeShard(await session.read(f), now);
        } catch (e) {
          if (e instanceof SyncFormatError) { damaged++; continue; }
          throw e;
        }
        if (!shard) continue; // newer format: leave it for a newer version
        if (shard.device !== dev) { damaged++; continue; }
        m.devices[dev] = { name: shard.name, seen: Math.max(f.modifiedAt ?? 0, m.devices[dev]?.seen ?? 0) };
        for (const r of shard.records) {
          const k = recKey(r.kind, r.id);
          received.set(k, received.has(k) ? merge(received.get(k), r) : r);
        }
        newSeen[f.name] = f.md5;
      }

      // Merge in (state-based: everything we have is kept, plus what's new).
      const mine = await this.records();
      const changed = [];
      for (const [k, r] of received) {
        clock.observe(maxTs(r));
        const cur = mine.get(k);
        const merged = cur ? merge(cur, r) : r;
        if (cur && JSON.stringify(encodeRecord(merged)) === JSON.stringify(encodeRecord(cur))) continue;
        changed.push(merged);
      }
      if (changed.length) {
        const shards = await this.putRecords(changed);
        m.dirty = [...new Set([...m.dirty, ...shards])];
      }
      m.seenMd5 = newSeen;
      m.clock = Math.max(m.clock, clock.last);
      await this.saveMeta(m);

      // Upload this browser's files that changed.
      let sent = 0;
      const all = await this.records();
      for (const n of [...m.dirty].sort((a, b) => a - b)) {
        const recs = [...all.values()].filter((r) => shardOf(r.id) === n);
        const name = shardName(m.deviceId, n);
        await session.write(name, encodeShard(m.deviceId, m.deviceName, n, now, recs), ours[name] ?? null);
        m.dirty = m.dirty.filter((x) => x !== n);
        await this.saveMeta(m);
        sent++;
      }
      m.account = (await session.accountEmail().catch(() => null)) || m.account;
      m.lastSyncAt = this.now();
      m.lastError = damaged ? `Skipped ${damaged} damaged file(s) from another device; nothing from them was applied` : '';
      m.needs = null;
      await this.saveMeta(m);
      return { ok: true, received: changed.length, sent, damaged };
    } catch (e) {
      if (e?.status === 401) return this.fail(m, 'Sign in to Google again to sync', false, 'signin');
      const transient = !e?.status || e.status === 403 || e.status === 429 || e.status >= 500;
      return this.fail(m, e?.status ? `Google Drive had a problem (${e.status})` : 'Couldn\'t reach Google Drive', transient);
    }
  }

  async fail(m, message, retry, needs = null) {
    m.lastError = message;
    m.needs = needs;
    await this.saveMeta(m);
    return { ok: false, message, retry, needs };
  }
}

/** An in-memory store with chrome.storage.local's shape (tests, and a fallback). */
export function memoryStore() {
  const data = new Map();
  return {
    async get(keys) {
      const out = {};
      for (const k of keys) if (data.has(k)) out[k] = structuredClone(data.get(k));
      return out;
    },
    async set(items) {
      for (const [k, v] of Object.entries(items)) data.set(k, structuredClone(v));
    },
  };
}
