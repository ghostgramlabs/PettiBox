// The sync data model and merge rules: a line-for-line port of
// app/src/main/java/com/ghostgramlabs/pettibox/data/sync/SyncModel.kt.
// Both must reproduce every case in sync-spec/vectors/cases.json; see
// sync-spec/README.md for the rules in prose.
//
// Records are plain objects:
//   { kind, id, created, vv: {dev: ts}, fields: {name: [Head]}, tags: {key: [TagDot]},
//     files: {sha: [FileDot]}, gone: {dev: ts} | null }
//   Head    = { value, dot: {dev, ts}, ctx?: {dev: ts} }
//   TagDot  = { name, dot }
//   FileDot = { kind, order, dot }

export const KIND_ITEM = 'item';
export const KIND_COLLECTION = 'col';

export const F = {
  TITLE: 'title', URL: 'url', NOTES: 'notes', OCR: 'ocr', META: 'meta', CAT: 'cat',
  TYPE: 'type', SRC: 'src', FAV: 'fav', PIN: 'pin', ARCH: 'arch', REMIND: 'remind',
  OPENED: 'opened', TRASH: 'trash', MAIN: 'main', THUMB: 'thumb',
  NAME: 'name', EMOJI: 'emoji', COLOR: 'color', ORDER: 'order', PARENT: 'parent', USER: 'user', LOCKED: 'locked',
};
export const FILE_OCR_PREFIX = 'ocr:';
const DERIVED = new Set([F.OCR, F.META, F.OPENED, F.THUMB]);

// ── Dots and contexts ────────────────────────────────────────────────────

/** Orders writes by time, then device id (string order, as Kotlin's compareTo). */
export function cmpDot(a, b) {
  if (a.ts !== b.ts) return a.ts < b.ts ? -1 : 1;
  return a.dev < b.dev ? -1 : a.dev > b.dev ? 1 : 0;
}
const sameDot = (a, b) => a.ts === b.ts && a.dev === b.dev;
const dotKey = (d) => d.ts + '|' + d.dev;

export function covers(ctx, dot) {
  return (ctx[dot.dev] ?? 0) >= dot.ts;
}

export function maxCtx(a, b) {
  if (Object.keys(a).length === 0) return b;
  if (Object.keys(b).length === 0) return a;
  const out = { ...a };
  for (const [dev, ts] of Object.entries(b)) out[dev] = Math.max(out[dev] ?? 0, ts);
  return out;
}

function bump(vv, dot) {
  return { ...vv, [dot.dev]: Math.max(vv[dot.dev] ?? 0, dot.ts) };
}

// ── Reading a record ─────────────────────────────────────────────────────

function newest(list) {
  let best = null;
  for (const x of list) if (best === null || cmpDot(x.dot, best.dot) > 0) best = x;
  return best;
}

export function has(r, field) {
  return (r.fields[field]?.length ?? 0) > 0;
}

/** The value shown: the newest of the concurrent values. */
export function value(r, field) {
  const h = newest(r.fields[field] ?? []);
  return h === null ? null : h.value;
}

/** Other values written concurrently with the shown one (distinct, oldest first). */
export function alternatives(r, field) {
  const heads = r.fields[field] ?? [];
  const shown = newest(heads);
  if (shown === null) return [];
  const out = [];
  const seen = new Set();
  for (const h of heads) {
    if (sameDot(h.dot, shown.dot) || valueEq(h.value, shown.value)) continue;
    const k = JSON.stringify(h.value);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(h);
  }
  return out;
}

export function presentTags(r) {
  const out = {};
  for (const [key, dots] of Object.entries(r.tags)) if (dots.length) out[key] = newest(dots).name;
  return out;
}

export function presentFiles(r) {
  const out = {};
  for (const [sha, dots] of Object.entries(r.files)) if (dots.length) out[sha] = newest(dots);
  return out;
}

function* userDots(r, excludeTrash) {
  for (const [name, heads] of Object.entries(r.fields)) {
    if (DERIVED.has(name) || name.startsWith(FILE_OCR_PREFIX)) continue;
    if (excludeTrash && name === F.TRASH) continue;
    for (const h of heads) yield h.dot;
  }
  for (const dots of Object.values(r.tags)) for (const t of dots) yield t.dot;
  for (const dots of Object.values(r.files)) for (const f of dots) yield f.dot;
}

function all(iter, pred) {
  for (const x of iter) if (!pred(x)) return false;
  return true;
}

export function isGone(r) {
  return r.gone != null && all(userDots(r, false), (d) => covers(r.gone, d));
}

export function isTrashed(r) {
  const head = newest(r.fields[F.TRASH] ?? []);
  if (head === null || head.value === null) return false;
  if (!head.ctx) return true;
  return all(userDots(r, true), (d) => covers(head.ctx, d));
}

export function trashedAt(r) {
  return isTrashed(r) ? value(r, F.TRASH) : null;
}

export function maxTs(r) {
  let m = 0;
  for (const ts of Object.values(r.vv)) if (ts > m) m = ts;
  return m;
}

export function hasConflict(r) {
  return Object.keys(r.fields).some((f) => alternatives(r, f).length > 0);
}

// ── Merge ────────────────────────────────────────────────────────────────

/** Commutative, associative, idempotent: devices converge in any sync order. */
export function merge(a, b) {
  if (a.kind !== b.kind || a.id !== b.id) throw new Error('merging different records');
  let gone;
  if (a.gone == null) gone = b.gone ?? null;
  else if (b.gone == null) gone = a.gone;
  else gone = maxCtx(a.gone, b.gone);
  return {
    kind: a.kind,
    id: a.id,
    created: Math.min(a.created, b.created),
    vv: maxCtx(a.vv, b.vv),
    fields: mergeKeyed(a.fields, a.vv, b.fields, b.vv, headTie),
    tags: mergeKeyed(a.tags, a.vv, b.tags, b.vv, (t) => 't:' + t.name),
    files: mergeKeyed(a.files, a.vv, b.files, b.vv, (f) => 'f:' + f.kind + '|' + f.order),
    gone,
  };
}

// Keep an element if the other copy has it too, or hasn't seen its write.
function mergeKeyed(a, aSeen, b, bSeen, tie) {
  const out = {};
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const key of keys) {
    const xs = a[key] ?? [];
    const ys = b[key] ?? [];
    const yDots = new Set(ys.map((y) => dotKey(y.dot)));
    const xDots = new Set(xs.map((x) => dotKey(x.dot)));
    const kept = new Map();
    for (const x of xs) if (yDots.has(dotKey(x.dot)) || !covers(bSeen, x.dot)) kept.set(dotKey(x.dot), x);
    for (const y of ys) {
      if (xDots.has(dotKey(y.dot)) || !covers(aSeen, y.dot)) {
        const k = dotKey(y.dot);
        const existing = kept.get(k);
        // Same write, two different values (corruption): pick the same way as Kotlin.
        kept.set(k, existing === undefined ? y : (tie(y) < tie(existing) ? y : existing));
      }
    }
    if (kept.size) out[key] = [...kept.values()].sort((p, q) => cmpDot(p.dot, q.dot));
  }
  return out;
}

// Order for two values claiming the same write (only when a file is
// corrupt). Must match tieKey() in SyncModel.kt exactly.
function headTie(h) {
  const x = h.value;
  const v = x === null ? 'null' : typeof x === 'string' ? 's:' + x : typeof x === 'boolean' ? 'b:' + x : 'n:' + x;
  const ctx = h.ctx ? Object.keys(h.ctx).sort().map((k) => k + '=' + h.ctx[k]).join(',') : '-';
  return 'h:' + v + '|' + ctx;
}

function valueEq(a, b) {
  return a === b;
}

// ── Local writes ─────────────────────────────────────────────────────────

export const Write = {
  field(r, field, val, dot, ctx = null) {
    const head = ctx ? { value: val, dot, ctx } : { value: val, dot };
    return { ...r, fields: { ...r.fields, [field]: [head] }, vv: bump(r.vv, dot) };
  },
  trash(r, deletedAt, dot) {
    return Write.field(r, F.TRASH, deletedAt, dot, deletedAt != null ? r.vv : null);
  },
  addTag(r, key, name, dot) {
    const list = [...(r.tags[key] ?? []), { name, dot }].sort((p, q) => cmpDot(p.dot, q.dot));
    return { ...r, tags: { ...r.tags, [key]: list }, vv: bump(r.vv, dot) };
  },
  removeTag(r, key, dot) {
    const tags = { ...r.tags };
    delete tags[key];
    return { ...r, tags, vv: bump(r.vv, dot) };
  },
  addFile(r, sha, kind, order, dot) {
    return { ...r, files: { ...r.files, [sha]: [{ kind, order, dot }] }, vv: bump(r.vv, dot) };
  },
  removeFile(r, sha, dot) {
    const files = { ...r.files };
    delete files[sha];
    return { ...r, files, vv: bump(r.vv, dot) };
  },
  gone(r, dot) {
    const vv = bump(r.vv, dot);
    return { ...r, vv, gone: maxCtx(r.gone ?? {}, vv) };
  },
};

/** Hybrid clock: a new write is later than the wall clock and every write seen. */
export class HybridClock {
  constructor(last, now = () => Date.now()) {
    this.last = last;
    this.now = now;
  }
  observe(ts) {
    if (ts > this.last) this.last = ts;
  }
  next() {
    this.last = Math.max(this.now(), this.last + 1);
    return this.last;
  }
}

// ── Local state folded into the merged copy ──────────────────────────────
// local = { kind, id, created, fields: {name: value}, tags: {key: name}, files: {sha: {kind, order}} }

const localTrashed = (l) => l.fields[F.TRASH] != null;

export function diff(meta, local, dot) {
  if (local == null) {
    if (meta == null || isGone(meta)) return null;
    return Write.gone(meta, dot);
  }
  if (meta == null) return writeAll(emptyOf(local), local, dot);
  if (isGone(meta)) {
    if (localTrashed(local)) return null;
    return writeAll(meta, local, dot);
  }
  let r = meta;
  let changed = false;
  for (const [name, val] of Object.entries(local.fields)) {
    if (name === F.TRASH) {
      if (trashedAt(meta) === val) continue;
      r = Write.trash(r, val, dot);
    } else {
      if (has(meta, name) ? valueEq(value(meta, name), val) : val == null) continue;
      r = Write.field(r, name, val, dot);
    }
    changed = true;
  }
  const present = presentTags(meta);
  for (const [key, name] of Object.entries(local.tags)) {
    if (!(key in present)) { r = Write.addTag(r, key, name, dot); changed = true; }
  }
  for (const key of Object.keys(present)) {
    if (!(key in local.tags)) { r = Write.removeTag(r, key, dot); changed = true; }
  }
  const files = presentFiles(meta);
  for (const [sha, f] of Object.entries(local.files)) {
    const now = files[sha];
    if (!now || now.kind !== f.kind || now.order !== f.order) {
      r = Write.addFile(r, sha, f.kind, f.order, dot); changed = true;
    }
  }
  for (const sha of Object.keys(files)) {
    if (!(sha in local.files)) { r = Write.removeFile(r, sha, dot); changed = true; }
  }
  if (!changed) return null;
  // Live only because an edit beat a delete: settle that now.
  if (meta.gone != null) return writeAll(r, local, dot);
  if (value(meta, F.TRASH) != null && !isTrashed(meta) && !localTrashed(local) && value(r, F.TRASH) != null) {
    r = Write.trash(r, null, dot);
  }
  return r;
}

function emptyOf(local) {
  return { kind: local.kind, id: local.id, created: local.created, vv: {}, fields: {}, tags: {}, files: {}, gone: null };
}

function writeAll(base, local, dot) {
  let r = base;
  for (const [name, val] of Object.entries(local.fields)) {
    r = name === F.TRASH ? Write.trash(r, val, dot) : Write.field(r, name, val, dot);
  }
  for (const [key, name] of Object.entries(local.tags)) {
    const tags = { ...r.tags };
    delete tags[key];
    r = Write.addTag({ ...r, tags }, key, name, dot);
  }
  for (const key of Object.keys(r.tags)) if (!(key in local.tags)) r = Write.removeTag(r, key, dot);
  for (const [sha, f] of Object.entries(local.files)) r = Write.addFile(r, sha, f.kind, f.order, dot);
  for (const sha of Object.keys(r.files)) if (!(sha in local.files)) r = Write.removeFile(r, sha, dot);
  return r;
}

/** Which of a device's 16 files a record lives in: FNV-1a over UTF-8, top 4 bits. */
export function shardOf(id) {
  const bytes = new TextEncoder().encode(id);
  let h = 0x811c9dc5;
  for (const b of bytes) {
    h ^= b;
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 28) & 0xf;
}
