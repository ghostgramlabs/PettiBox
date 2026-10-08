// JSON for records and device files; mirrors SyncCodec.kt. Format in
// sync-spec/README.md.

import { KIND_COLLECTION, KIND_ITEM, maxTs } from './model.js';

export const FORMAT = 1;
const MAX_FUTURE_MS = 10 * 365 * 24 * 60 * 60 * 1000;

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

function ctxOf(o) {
  const out = {};
  for (const [dev, ts] of Object.entries(o)) {
    if (typeof ts !== 'number') throw new SyncFormatError(`bad clock for ${dev}`);
    out[dev] = Math.trunc(ts);
  }
  return out;
}

function dotOf(o, where) {
  if (typeof o.t !== 'number' || typeof o.d !== 'string' || !o.d.trim()) throw new SyncFormatError(`${where}: bad write stamp`);
  return { dev: o.d, ts: Math.trunc(o.t) };
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

export function encodeShard(device, name, shard, generated, records) {
  const sorted = [...records].sort((a, b) => {
    const ka = a.kind + ':' + a.id;
    const kb = b.kind + ':' + b.id;
    return ka < kb ? -1 : ka > kb ? 1 : 0;
  });
  return JSON.stringify({ format: FORMAT, device, name, shard, generated, records: sorted.map(encodeRecord) });
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
  const limit = now + MAX_FUTURE_MS;
  const records = root.records.map((o, i) => {
    if (!o || typeof o !== 'object') throw new SyncFormatError(`record ${i} not an object`);
    const r = decodeRecord(o);
    if (maxTs(r) > limit) throw new SyncFormatError(`record ${r.id} is dated far in the future`);
    return r;
  });
  return {
    format,
    device: root.device,
    name: typeof root.name === 'string' ? root.name : '',
    shard: Number.isInteger(root.shard) ? root.shard : 0,
    generated: Number.isInteger(root.generated) ? root.generated : 0,
    records,
  };
}
