// The JavaScript engine must reproduce every golden case the Kotlin engine
// generated (sync-spec/vectors/cases.json). Run: node --test extension/test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  merge, diff, isGone, isTrashed, trashedAt, value, hasConflict, presentTags, presentFiles, shardOf,
} from '../sync/model.js';
import { decodeRecord, encodeRecord, decodeShard, encodeShard, SyncFormatError } from '../sync/codec.js';

const here = dirname(fileURLToPath(import.meta.url));
const cases = JSON.parse(readFileSync(join(here, '../../sync-spec/vectors/cases.json'), 'utf8'));

/** Key-sorted JSON, so equal values compare equal whatever their key order. */
function canonical(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (typeof v === 'object') return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  return JSON.stringify(v);
}

test(`merge reproduces all ${cases.merge.length} Kotlin cases`, () => {
  cases.merge.forEach((c, i) => {
    const m = merge(decodeRecord(c.a), decodeRecord(c.b));
    assert.equal(canonical(encodeRecord(m)), canonical(c.merged), `merge case ${i}`);
    const view = {
      gone: isGone(m),
      trashed: isTrashed(m),
      trashedAt: trashedAt(m),
      title: value(m, 'title'),
      notes: value(m, 'notes'),
      conflict: hasConflict(m),
      tags: presentTags(m),
      files: Object.keys(presentFiles(m)).sort(),
    };
    assert.equal(canonical(view), canonical(c.view), `view of merge case ${i}`);
  });
});

test(`local diff reproduces all ${cases.diff.length} Kotlin cases`, () => {
  cases.diff.forEach((c, i) => {
    const meta = c.meta === null ? null : decodeRecord(c.meta);
    const local = c.local === null ? null : {
      kind: c.local.k, id: c.local.id, created: c.local.c,
      fields: c.local.f, tags: c.local.tags,
      files: Object.fromEntries(Object.entries(c.local.att).map(([sha, f]) => [sha, { kind: f.k, order: f.o }])),
    };
    const out = diff(meta, local, { dev: c.dot.d, ts: c.dot.t });
    assert.equal(canonical(out === null ? null : encodeRecord(out)), canonical(c.result), `diff case ${i}`);
  });
});

test('merge laws hold on the case records', () => {
  const recs = cases.merge.slice(0, 60).flatMap((c) => [decodeRecord(c.a), decodeRecord(c.b)]);
  for (let i = 0; i + 2 < recs.length; i += 3) {
    const [a, b, c] = [recs[i], recs[i + 1], recs[i + 2]];
    const e = (r) => canonical(encodeRecord(r));
    assert.equal(e(merge(a, b)), e(merge(b, a)));
    assert.equal(e(merge(merge(a, b), c)), e(merge(a, merge(b, c))));
    assert.equal(e(merge(merge(a, b), b)), e(merge(a, b)));
  }
});

test('shards match Kotlin for known ids', () => {
  // Same FNV-1a as SyncModel.kt's shardOf; values computed there.
  for (const [id, n] of Object.entries(cases.shards)) assert.equal(shardOf(id), n, `shard of '${id}'`);
  const counts = new Set();
  for (let i = 0; i < 2000; i++) counts.add(shardOf(Math.random().toString(16).slice(2)));
  assert.equal(counts.size, 16);
});

test('a broken file is rejected and a newer format is left alone', () => {
  assert.equal(decodeShard('{"format":99,"device":"x","records":[]}'), null);
  for (const bad of [
    'not json',
    '{"format":1,"records":[]}',
    '{"format":1,"device":"x","records":[{"k":"item","id":"a"}]}',
    '{"format":1,"device":"x","records":[{"k":"item","id":"a","vv":{},"f":{"title":[{"v":"x","t":"soon","d":"A"}]}}]}',
  ]) {
    assert.throws(() => decodeShard(bad), SyncFormatError, bad);
  }
  const r = decodeRecord(cases.merge[0].merged);
  const back = decodeShard(encodeShard('dev1', 'Chrome', 3, 5, [r]));
  assert.equal(canonical(encodeRecord(back.records[0])), canonical(encodeRecord(r)));
});
