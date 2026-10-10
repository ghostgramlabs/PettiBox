// JSON for records and device files; mirrors SyncCodec.kt. Format in
// sync-spec/README.md.

import { KIND_COLLECTION, KIND_ITEM, maxTs, F, FILE_OCR_PREFIX } from './model.js';

export const FORMAT = 1;
const MAX_FUTURE_MS = 10 * 365 * 24 * 60 * 60 * 1000;
/** Bounds shared with SyncCodec.kt: larger is corrupt, not a big library. */
export const MAX_SHARD_RECORDS = 200_000;
const MAX_ID = 200;

/**
 * The type each known field must have (null is always allowed). Unknown
 * fields may hold any plain value and pass through untouched.
 */
const FIELD_TYPES = {
  [F.TITLE]: 'string', [F.URL]: 'string', [F.NOTES]: 'string', [F.OCR]: 'string', [F.META]: 'string',
  [F.CAT]: 'string', [F.TYPE]: 'string', [F.SRC]: 'string', [F.MAIN]: 'string', [F.THUMB]: 'string',
  [F.FAV]: 'boolean', [F.PIN]: 'boolean', [F.ARCH]: 'boolean',
  [F.REMIND]: 'number', [F.OPENED]: 'number', [F.TRASH]: 'number',
  [F.NAME]: 'string', [F.EMOJI]: 'string', [F.PARENT]: 'string',
  [F.COLOR]: 'number', [F.ORDER]: 'number', [F.USER]: 'boolean', [F.LOCKED]: 'boolean',
};
const typeOfField = (name) => FIELD_TYPES[name] ?? (name.startsWith(FILE_OCR_PREFIX) ? 'string' : null);

export class SyncFormatError extends Error {}

const byDot = (p, q) => (p.dot.ts - q.dot.ts) || (p.dot.dev < q.dot.dev ? -1 : p.dot.dev > q.dot.dev ? 1 : 0);

export function encodeRecord(r) {
  const o = { k: r.kind, id: r.id, c: r.created, vv: { ...r.vv }, f: {} };
  for (const [name, heads] of Object.entries(r.fields)) {
    o.f[name] = heads.map((h) => {
      const e = { v: h.value === undefined ? null : h.value, t: h.dot.ts, d: h.dot.dev };
      if (h.ctx) e.x = { ...h.ctx };
      return e;
    });
  }
  if (Object.keys(r.tags).length) {
    o.tags = {};
    for (const [key, dots] of Object.entries(r.tags)) o.tags[key] = dots.map((t) => ({ n: t.name, t: t.dot.ts, d: t.dot.dev }));
  }
  if (Object.keys(r.files).length) {
    o.att = {};
    for (const [sha, dots] of Object.entries(r.files)) o.att[sha] = dots.map((f) => ({ k: f.kind, o: f.order, t: f.dot.ts, d: f.dot.dev }));
  }
  if (r.gone) o.gone = { ...r.gone };
  return o;
}

export function decodeRecord(o) {
  if (!o || typeof o !== 'object') throw new SyncFormatError('record not an object');
  const kind = o.k;
  if (kind !== KIND_ITEM && kind !== KIND_COLLECTION) throw new SyncFormatError(`unknown kind '${kind}'`);
  const id = typeof o.id === 'string' ? o.id : '';
  if (!id.trim()) throw new SyncFormatError('record without id');
  if (!o.vv || typeof o.vv !== 'object') throw new SyncFormatError(`${id}: no vv`);
  const fields = {};
  for (const [name, arr] of Object.entries(o.f ?? {})) {
    if (!Array.isArray(arr)) throw new SyncFormatError(`${id}.${name}: not a list`);
    const heads = arr.map((h) => {
      if (!h || typeof h !== 'object') throw new SyncFormatError(`${id}.${name}`);
      const head = { value: valueOf(h.v, `${id}.${name}`), dot: dotOf(h, `${id}.${name}`) };
      if (h.x && typeof h.x === 'object') head.ctx = ctxOf(h.x);
      return head;
    });
    if (heads.length) fields[name] = heads.sort(byDot);
  }
  const tags = {};
  for (const [key, arr] of Object.entries(o.tags ?? {})) {
    if (!Array.isArray(arr)) throw new SyncFormatError(`${id} tag ${key}`);
    const dots = arr.map((e) => ({ name: typeof e?.n === 'string' ? e.n : key, dot: dotOf(e ?? {}, `${id} tag ${key}`) }));
    if (dots.length) tags[key] = dots.sort(byDot);
  }
  const files = {};
  for (const [sha, arr] of Object.entries(o.att ?? {})) {
    if (!Array.isArray(arr)) throw new SyncFormatError(`${id} file ${sha}`);
    const dots = arr.map((e) => ({
      kind: typeof e?.k === 'string' ? e.k : 'FILE',
      order: Number.isInteger(e?.o) ? e.o : 0,
      dot: dotOf(e ?? {}, `${id} file ${sha}`),
    }));
    if (dots.length) files[sha] = dots.sort(byDot);
  }
  return {
    kind,
    id,
    created: Number.isInteger(o.c) ? o.c : 0,
    vv: ctxOf(o.vv),
    fields,
    tags,
    files,
    gone: o.gone && typeof o.gone === 'object' ? ctxOf(o.gone) : null,
  };
}

/** A time: a whole, non-negative number both apps can hold exactly. */
const isStamp = (n) => Number.isSafeInteger(n) && n >= 0;

function ctxOf(o) {
  const out = {};
  for (const [dev, ts] of Object.entries(o)) {
    if (!isStamp(ts) || !dev.trim()) throw new SyncFormatError(`bad clock for ${dev}`);
    out[dev] = ts;
  }
  return out;
}

function dotOf(o, where) {
  if (!isStamp(o.t) || typeof o.d !== 'string' || !o.d.trim()) throw new SyncFormatError(`${where}: bad write stamp`);
  return { dev: o.d, ts: o.t };
}

/**
 * Checks a record from another device before anything of it is kept: known
 * fields have their type, and its clock covers every write in it (so no
 * write can hide past the future-date check, and merges stay sound).
 */
export function validateRecord(r) {
  if (r.id.length > MAX_ID) throw new SyncFormatError('id too long');
  if (!isStamp(r.created)) throw new SyncFormatError(`${r.id}: bad created time`);
  const covered = (dot, where) => {
    if (!((r.vv[dot.dev] ?? -1) >= dot.ts)) throw new SyncFormatError(`${r.id}: ${where} written outside its clock`);
  };
  for (const [name, heads] of Object.entries(r.fields)) {
    const type = typeOfField(name);
    for (const h of heads) {
      covered(h.dot, name);
      if (type && h.value !== null && typeof h.value !== type) throw new SyncFormatError(`${r.id}.${name}: not a ${type}`);
    }
  }
  for (const [key, dots] of Object.entries(r.tags)) {
    if (key.length > MAX_ID) throw new SyncFormatError(`${r.id}: tag too long`);
    for (const t of dots) covered(t.dot, `tag ${key}`);
  }
  for (const [sha, dots] of Object.entries(r.files)) {
    if (!/^[0-9a-f]{1,64}$/.test(sha)) throw new SyncFormatError(`${r.id}: bad file name`);
    for (const f of dots) covered(f.dot, 'file');
  }
}

function valueOf(v, where) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'string' || typeof v === 'boolean') return v;
  if (typeof v === 'number') {
    if (!Number.isInteger(v)) throw new SyncFormatError(`${where}: not a whole number`);
    return v;
  }
  throw new SyncFormatError(`${where}: unsupported value`);
}

/**
 * [epoch]: the reset marker this file was written under ("" before any
 * reset). Readers skip files from another epoch, so nothing written before a
 * "Delete synced data" can come back after it.
 */
export function encodeShard(device, name, shard, generated, records, epoch = '') {
  const sorted = [...records].sort((a, b) => {
    const ka = a.kind + ':' + a.id;
    const kb = b.kind + ':' + b.id;
    return ka < kb ? -1 : ka > kb ? 1 : 0;
  });
  const root = { format: FORMAT, device, name, shard, generated };
  if (epoch) root.epoch = epoch;
  root.records = sorted.map(encodeRecord);
  return JSON.stringify(root);
}

/** Null for a newer format (leave it for a newer app); throws SyncFormatError if broken. */
export function decodeShard(text, now = Date.now()) {
  let root;
  try {
    root = JSON.parse(text);
  } catch {
    throw new SyncFormatError('not JSON');
  }
  const format = Number.isInteger(root?.format) ? root.format : -1;
  if (format > FORMAT) return null;
  if (format !== FORMAT) throw new SyncFormatError(`bad format ${format}`);
  if (typeof root.device !== 'string' || !root.device.trim()) throw new SyncFormatError('no device');
  if (!Array.isArray(root.records)) throw new SyncFormatError('no records');
  if (root.records.length > MAX_SHARD_RECORDS) throw new SyncFormatError('too many records');
  const shard = root.shard;
  if (!Number.isInteger(shard) || shard < 0 || shard > 15) throw new SyncFormatError(`bad shard ${shard}`);
  const limit = now + MAX_FUTURE_MS;
  const seen = new Set();
  const records = root.records.map((o, i) => {
    if (!o || typeof o !== 'object') throw new SyncFormatError(`record ${i} not an object`);
    const r = decodeRecord(o);
    validateRecord(r);
    if (maxTs(r) > limit) throw new SyncFormatError(`record ${r.id} is dated far in the future`);
    const key = r.kind + ':' + r.id;
    if (seen.has(key)) throw new SyncFormatError(`record ${r.id} appears twice`);
    seen.add(key);
    return r;
  });
  return {
    format,
    device: root.device,
    name: typeof root.name === 'string' ? root.name : '',
    shard,
    generated: Number.isSafeInteger(root.generated) ? root.generated : 0,
    epoch: typeof root.epoch === 'string' ? root.epoch : '',
    records,
  };
}
