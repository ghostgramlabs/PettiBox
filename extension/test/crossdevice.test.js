// Device files cross between implementations: the extension reads a file the
// app wrote (cases.json "shard"), and writes one the app's
// SyncVectorsTest reads back (from-extension.json).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { decodeShard, encodeShard, encodeRecord, decodeRecord } from '../sync/codec.js';
import { diff, F, KIND_ITEM, KIND_COLLECTION, value, presentTags } from '../sync/model.js';

const dir = join(dirname(fileURLToPath(import.meta.url)), '../../sync-spec/vectors');
const cases = JSON.parse(readFileSync(join(dir, 'cases.json'), 'utf8'));

const canonical = (v) => v === null || v === undefined ? 'null'
  : Array.isArray(v) ? '[' + v.map(canonical).join(',') + ']'
    : typeof v === 'object' ? '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}'
      : JSON.stringify(v);

test('the extension reads a device file the app wrote', () => {
  const shard = decodeShard(cases.shard, 1_760_000_000_000);
  assert.equal(shard.device, '0123456789abcdef');
  assert.equal(shard.name, 'Pixel 8');
  assert.equal(shard.shard, 3);
  assert.equal(shard.records.length, 5);
  shard.records.forEach((r, i) => {
    const expected = { ...cases.merge[i].merged, id: `s${i}` };
    assert.equal(canonical(encodeRecord(r)), canonical(expected), `record ${i}`);
  });
});

/** A fixed library as the extension would write it: a collection and two saves. */
export function extensionFile() {
  const dev = 'fedcba9876543210';
  const col = diff(null, {
    kind: KIND_COLLECTION, id: 'user_ext00001', created: 1_760_000_000_000,
    fields: { [F.NAME]: 'From Chrome', [F.EMOJI]: '📦', [F.COLOR]: 0xff8b5cf6, [F.ORDER]: 7, [F.PARENT]: null, [F.USER]: true, [F.LOCKED]: false },
    tags: {}, files: {},
  }, { dev, ts: 1_760_000_000_001 });
  const link = (id, title, url, ts, tags, notes) => diff(null, {
    kind: KIND_ITEM, id, created: ts,
    fields: {
      [F.TITLE]: title, [F.URL]: url, [F.NOTES]: notes, [F.OCR]: null, [F.META]: null, [F.CAT]: 'user_ext00001',
      [F.TYPE]: 'LINK', [F.SRC]: 'CHROME', [F.FAV]: false, [F.PIN]: false, [F.ARCH]: false, [F.REMIND]: null,
      [F.OPENED]: null, [F.TRASH]: null, [F.MAIN]: null,
    },
    tags, files: {},
  }, { dev, ts });
  const a = link('0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a', 'Saved in Chrome', 'https://example.com/chrome', 1_760_000_000_010, { reading: 'Reading' }, 'with a note');
  const b = link('0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b', 'Ünïcødé title ✓', 'https://example.com/ü', 1_760_000_000_020, {}, null);
  return encodeShard(dev, 'Chrome on laptop', 5, 1_760_000_000_100, [col, a, b]);
}

test('the extension writes a device file the app can read (fixture kept in step)', () => {
  const path = join(dir, 'from-extension.json');
  const text = extensionFile();
  if (process.env.WRITE_SYNC_VECTORS === 'true' || !existsSync(path)) writeFileSync(path, text + '\n');
  assert.equal(readFileSync(path, 'utf8').trim(), text);
  const back = decodeShard(text, 1_760_000_000_100);
  const saved = back.records.find((r) => r.id.startsWith('0a'));
  assert.equal(value(saved, F.TITLE), 'Saved in Chrome');
  assert.deepEqual(presentTags(saved), { reading: 'Reading' });
  assert.equal(canonical(encodeRecord(decodeRecord(encodeRecord(saved)))), canonical(encodeRecord(saved)));
});
