// Acceptance tests for the sync design audit (audit/SYNC_SYSTEM_DESIGN_AUDIT.md).
// Each one replays an audit probe and requires the fixed behaviour.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { SyncEngine, memoryStore, shardName } from '../sync/engine.js';
import { encodeShard, decodeShard } from '../sync/codec.js';
import { shardOf, diff } from '../sync/model.js';
import { installFakeDrive } from './fakeDrive.js';

function drive() {
  const files = new Map();
  const d = {
    files, marker: '', afterList: null,
    async open() {
      return {
        accountEmail: async () => 'audit@example.com',
        resetMarker: async () => d.marker,
        list: async () => {
          const listed = [...files].map(([name, f]) => ({ name, id: name, md5: f.md5 }));
          await d.afterList?.();
          return listed;
        },
        read: async (f) => files.get(f.name).json,
        write: async (name, json, existingId) => {
          if (existingId && !files.has(existingId)) throw Object.assign(new Error('not found'), { status: 404 });
          files.set(name, { json, md5: createHash('md5').update(json).digest('hex') });
        },
      };
    },
  };
  return d;
}

test('D01: retry bookkeeping never erases an edit made meanwhile', async () => {
  const d = drive();
  const backing = memoryStore();
  let reached; let release;
  const ready = new Promise((r) => { reached = r; });
  const gate = new Promise((r) => { release = r; });
  let armed = false;
  const store = { get: backing.get, async set(items) {
    if (armed && items.meta?.retryAttempt === 1) { armed = false; reached(); await gate; }
    await backing.set(items);
  } };
  const engine = new SyncEngine(store, d);
  const { id } = await engine.saveLink({ url: 'https://audit.example/a', title: 'Before' });
  await engine.round();
  const source = await readFile(new URL('../background.js', import.meta.url), 'utf8');
  const from = source.indexOf('async function scheduleRetry(');
  const to = source.indexOf('\n}\n', from) + 2;
  const context = vm.createContext({ engine, chrome: { alarms: { async create() {}, async clear() {} } }, ALARM_RETRY: 'retry' });
  vm.runInContext(source.slice(from, to), context);
  armed = true;
  const retry = vm.runInContext('scheduleRetry({ ok: false, retry: true })', context);
  await ready;
  const edit = engine.updateItem(id, { title: 'Local edit after failure' }); // waits its turn
  release();
  await retry;
  await edit;
  assert.ok((await engine.meta()).dirty.length > 0);
  await engine.round();
  const peer = new SyncEngine(memoryStore(), d);
  await peer.round();
  assert.equal((await peer.library()).items[0].title, 'Local edit after failure');
});

test('D05: a reset during a round stops its uploads, and older files are not read after a reset', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  const first = await a.saveLink({ url: 'https://audit.example/reset', title: 'A' });
  await a.round();
  let id = 'new-record';
  while (shardOf(id) === shardOf(first.id)) id += 'x';
  await a.edit('item', id, (l) => l, { kind: 'item', id, created: Date.now(),
    fields: { url: 'https://audit.example/new', title: 'B', type: 'LINK', trash: null }, tags: {}, files: {} });
  d.afterList = async () => { d.files.clear(); d.marker = 'reset-2'; d.afterList = null; };
  const out = await a.round();
  assert.equal(out.ok, false);
  assert.equal(out.needs, 'reset');
  assert.equal(d.files.size, 0); // nothing written into the reset library

  // A writer that hadn't noticed the reset: its file has the old epoch and is skipped.
  const stale = encodeShard('bbbbbbbbbbbbbbbb', 'Old', shardOf('stale'), 1, [
    { kind: 'item', id: 'stale', created: 1, vv: { bbbbbbbbbbbbbbbb: 5 }, fields: { title: [{ value: 'stale', dot: { dev: 'bbbbbbbbbbbbbbbb', ts: 5 } }] }, tags: {}, files: {}, gone: null },
  ]);
  d.files.set(shardName('bbbbbbbbbbbbbbbb', shardOf('stale')), { json: stale, md5: 'x' });
  const c = new SyncEngine(memoryStore(), d);
  await c.round();
  assert.equal((await c.records()).size, 0);
});

test('D06a: merging folders never deletes something put there at that moment', async () => {
  const fake = installFakeDrive();
  const root1 = fake.folder('PettiBox');
  const root2 = fake.folder('PettiBox');
  const fetchDrive = globalThis.fetch;
  let late;
  globalThis.fetch = async (url, init = {}) => {
    if (init.method === 'PATCH' && new URL(url).pathname.endsWith('/' + root2) && String(init.body).includes('trashed')) {
      late = fake.make({ name: 'late-upload', parents: [root2] });
    }
    return fetchDrive(url, init);
  };
  const { driveTransport } = await import('../drive.js?audit-race');
  await (await driveTransport.open()).list(); // folders are settled on first use
  assert.ok(late);
  assert.ok(fake.files.has(late)); // in Drive's trash with its folder at worst: recoverable, never deleted
  assert.ok(fake.files.has(root1));
});

test('D06b: a photo in a second files folder is still found', async () => {
  const fake = installFakeDrive();
  const r = fake.folder('PettiBox');
  const s = fake.folder('Sync', r);
  fake.folder('files', s);
  const second = fake.folder('files', s);
  const sha = 'ab'.repeat(32);
  fake.make({ name: sha + '.jpg', parents: [second] });
  const { driveTransport } = await import('../drive.js?audit-folders');
  const session = await driveTransport.open();
  assert.ok((await session.storedFiles())[sha]);
});

test('D09: a reply that stalls half-way times out instead of hanging', async () => {
  const fake = installFakeDrive();
  fake.folder('PettiBox');
  const fetchDrive = globalThis.fetch;
  let signal;
  let first = true;
  globalThis.fetch = async (url, init) => {
    const response = await fetchDrive(url, init);
    if (!first) return response;
    first = false;
    signal = init.signal;
    return { ok: true, status: 200, json: () => new Promise(() => {}) }; // never finishes
  };
  const realTimer = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms, ...args) => realTimer(fn, Math.min(ms, 5), ...args);
  try {
    const { driveTransport } = await import('../drive.js?audit-timeout');
    const session = await driveTransport.open();
    await assert.rejects(session.list(), /too long/);
    assert.equal(signal.aborted, true);
  } finally {
    globalThis.setTimeout = realTimer;
  }
});

test('D10: a file with wrong field types, a bad shard or writes outside its clock is rejected whole', async () => {
  const dev = 'aaaaaaaaaaaaaaaa';
  const bad = (rec, shard = shardOf(rec.id)) => JSON.stringify({ format: 1, device: dev, shard, records: [rec] });
  const ok = { k: 'item', id: 'good', c: 1, vv: { [dev]: 5 }, f: { title: [{ v: 'Fine', t: 5, d: dev }] } };
  assert.equal(decodeShard(bad(ok)).records.length, 1);
  assert.throws(() => decodeShard(bad({ ...ok, f: { url: [{ v: 42, t: 5, d: dev }] } })), /not a string/);
  assert.throws(() => decodeShard(bad(ok, 99)), /bad shard/);
  assert.throws(() => decodeShard(bad({ ...ok, f: { title: [{ v: 'x', t: 999999999999999, d: dev }] } })), /outside its clock/);
  assert.throws(() => decodeShard(bad({ ...ok, vv: { [dev]: -1 } })), /bad clock/);
  assert.throws(() => decodeShard(bad({ ...ok, f: { title: [{ v: 'x', t: 1.5, d: dev }] } })), /bad write stamp/);

  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  const json = JSON.stringify({ format: 1, device: dev, shard: 99, records: [{ k: 'item', id: 'bad', c: 1,
    vv: { [dev]: 1 }, f: { url: [{ v: 42, t: 999999999999999, d: dev }], title: [{ v: 'Bad', t: 1, d: dev }] } }] });
  d.files.set(shardName(dev, 0), { json, md5: 'bad-wire' });
  const out = await a.round();
  assert.equal(out.damaged, 1);
  assert.equal((await a.records()).size, 0);
});

test('D10: a file whose body names a different shard than its file name is rejected', async () => {
  const dev = 'aaaaaaaaaaaaaaaa';
  const d = drive();
  const json = JSON.stringify({ format: 1, device: dev, shard: 3, records: [] });
  d.files.set(shardName(dev, 5), { json, md5: 'm' });
  const out = await new SyncEngine(memoryStore(), d).round();
  assert.equal(out.damaged, 1);
});

test('D11: this browser\'s own files missing from Drive are sent again', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  await a.saveLink({ url: 'https://audit.example/missing', title: 'A' });
  await a.round();
  d.files.clear();
  const out = await a.round();
  assert.equal(out.ok, true);
  assert.ok(out.sent > 0);
  const fresh = new SyncEngine(memoryStore(), d);
  await fresh.round();
  assert.equal((await fresh.library()).items.length, 1);
});

test('D12: a pending removal review doesn\'t hold back this browser\'s own saves, and new removals ask again', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  const b = new SyncEngine(memoryStore(), d);
  const ids = [];
  for (let i = 0; i < 7; i++) ids.push((await a.saveLink({ url: 'https://audit.example/review/' + i, title: String(i) })).id);
  await a.round(); await b.round();
  for (const id of ids.slice(0, 5)) await b.trash(id);
  await b.round();
  await a.saveLink({ url: 'https://audit.example/unrelated', title: 'Pending local save' });
  assert.equal((await a.round()).needs, 'review');
  await b.round();
  assert.ok((await b.library()).items.some((i) => i.title === 'Pending local save'));

  // "Apply" was chosen for those 5; two more removals arrive before the next round.
  const m = await a.meta();
  m.reviewDecision = 'apply';
  await a.saveMeta(m);
  for (const id of ids.slice(5)) await b.trash(id);
  await b.round();
  assert.equal((await a.round()).needs, 'review'); // a different set: asked again
});

test('O01: a newer-format file is reported, not silently skipped', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  d.files.set(shardName('aaaaaaaaaaaaaaaa', 0), { json: '{"format":2}', md5: 'v2' });
  await a.round();
  assert.match((await a.meta()).lastError, /newer PettiBox/);
});

// ── Recheck (audit/sync-design-recheck.mjs): the residual cases, now required to pass ──

test('recheck D02: if Drive can\'t say which account it is, nothing is synced', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  await a.saveLink({ url: 'https://audit.example/who', title: 'A' });
  const open = d.open.bind(d);
  d.open = async () => ({ ...(await open()), accountEmail: async () => null });
  const out = await a.round();
  assert.equal(out.ok, false);
  assert.equal(out.retry, true);
  assert.equal(d.files.size, 0);
});

test('recheck D05: a reset noticed while a review waits stops sync at once', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  const b = new SyncEngine(memoryStore(), d);
  const ids = [];
  for (let i = 0; i < 6; i++) ids.push((await a.saveLink({ url: 'https://audit.example/r/' + i, title: String(i) })).id);
  await a.round(); await b.round();
  for (const id of ids) await b.trash(id);
  await b.round();
  await a.saveLink({ url: 'https://audit.example/r/new', title: 'new' });
  const marker = d.open.bind(d);
  let calls = 0;
  d.open = async () => {
    const s = await marker();
    return { ...s, resetMarker: async () => (++calls > 1 ? 'reset-3' : '') };
  };
  const out = await a.round();
  assert.equal(out.needs, 'reset');
  assert.equal((await a.meta()).stopped, true);
});

test('recheck D06: a photo put in a duplicate files folder as it is retired is found again', async () => {
  const fake = installFakeDrive();
  const root = fake.folder('PettiBox');
  const folder = fake.folder('Sync', root);
  fake.folder('files', folder);
  const duplicate = fake.folder('files', folder);
  const sha = 'ab'.repeat(32);
  const driveFetch = globalThis.fetch;
  let late;
  globalThis.fetch = async (url, init = {}) => {
    if (!late && init.method === 'PATCH' && new URL(url).pathname.endsWith('/' + duplicate) && String(init.body).includes('trashed')) {
      late = fake.make({ name: sha + '.jpg', parents: [duplicate] });
    }
    return driveFetch(url, init);
  };
  const { driveTransport } = await import('../drive.js?recheck-folders');
  const session = await driveTransport.open();
  await session.storedFiles();
  assert.ok(late);
  const next = await driveTransport.open();
  assert.ok((await next.storedFiles())[sha]); // drained back into the live folder
});

test('recheck D08: a file a clean-up trashed is taken back out when a save needs it', async () => {
  const fake = installFakeDrive();
  const root = fake.folder('PettiBox');
  const folder = fake.folder('Sync', root);
  const files = fake.folder('files', folder);
  const sha = 'cd'.repeat(32);
  const id = fake.make({ name: sha + '.pdf', parents: [files] });
  fake.files.get(id).trashed = true;
  const { driveTransport } = await import('../drive.js?recheck-restore');
  const session = await driveTransport.open();
  assert.equal((await session.storedFiles())[sha], undefined);
  assert.ok(await session.restoreFile(sha));
  assert.ok((await session.storedFiles())[sha]);
});

test('recheck D10: a record in the wrong file, or a number Chrome can\'t hold exactly, rejects the file', async () => {
  const device = 'bbbbbbbbbbbbbbbb';
  const id = 'wrong-placement';
  const wrongShard = (shardOf(id) + 1) % 16;
  const record = { kind: 'item', id, created: 1, vv: { [device]: 1 },
    fields: { title: [{ value: 'Wrong shard', dot: { dev: device, ts: 1 } }] }, tags: {}, files: {}, gone: null };
  const json = encodeShard(device, 'Other', wrongShard, 1, [record]);
  const engine = new SyncEngine(memoryStore(), { async open() { return {
    accountEmail: async () => 'recheck@example.com', resetMarker: async () => '',
    list: async () => [{ id: 'remote', name: shardName(device, wrongShard), md5: 'one' }],
    read: async () => json, write: async () => {},
  }; } });
  const outcome = await engine.round();
  assert.equal(outcome.damaged, 1);
  assert.equal((await engine.records()).size, 0);

  const big = JSON.stringify({ format: 1, device, shard: shardOf(id), records: [{ k: 'item', id, c: 1, vv: { [device]: 1 },
    f: { remind: [{ v: Number.MAX_SAFE_INTEGER + 1, t: 1, d: device }] } }] });
  assert.throws(() => decodeShard(big), /safe whole number/);
});

// ── O03 / D08: stripping settled deletions, and saves that come back whole ──

/** A phone that removes a save for good and strips the marker (as its daily clean-up does once safe). */
async function removeForGoodAndStrip(engine, id) {
  const m = await engine.meta();
  const r = (await engine.records()).get('item:' + id);
  const gone = diff(r, null, { dev: m.deviceId, ts: Math.max(m.clock, Date.now()) + 1 });
  const bare = { ...gone, fields: {}, tags: {}, files: {} };
  const shards = await engine.putRecords([bare], m);
  m.dirty = [...new Set([...m.dirty, ...shards])];
  m.clock = Math.max(m.clock, Date.now()) + 1;
  await engine.saveMeta(m);
}

test('O03: a save brought back by an offline edit after its deletion was stripped comes back whole', async () => {
  const d = drive();
  const a = new SyncEngine(memoryStore(), d);
  const b = new SyncEngine(memoryStore(), d);
  const { id } = await a.saveLink({ url: 'https://audit.example/whole', title: 'Whole save', tags: ['bread'] });
  await a.round(); await b.round();
  await b.updateItem(id, { notes: 'written offline' }); // b hasn't heard of the deletion
  await removeForGoodAndStrip(a, id);
  await a.round();
  await b.round();
  await a.round();
  for (const e of [a, b]) {
    const s = (await e.library()).items.find((i) => i.id === id);
    assert.ok(s, 'brought back');
    assert.equal(s.title, 'Whole save');
    assert.equal(s.url, 'https://audit.example/whole');
    assert.equal(s.notes, 'written offline');
    assert.deepEqual(s.tags, ['bread']);
  }
});

test('O03: a save removed for good elsewhere can still be restored here for 30 days after it was stripped', async () => {
  const d = drive();
  const phone = new SyncEngine(memoryStore(), d);
  const chrome = new SyncEngine(memoryStore(), d);
  const { id } = await phone.saveLink({ url: 'https://audit.example/restore', title: 'Restore me' });
  await phone.round(); await chrome.round();
  await removeForGoodAndStrip(phone, id);
  await phone.round();
  await chrome.round();
  const trash = (await chrome.library({ trash: true })).items;
  assert.equal(trash.find((i) => i.id === id)?.title, 'Restore me');
  await chrome.restore(id);
  const back = (await chrome.library()).items.find((i) => i.id === id);
  assert.equal(back?.title, 'Restore me');
  assert.equal(back?.url, 'https://audit.example/restore');
  await chrome.round(); await phone.round();
  assert.equal((await phone.library()).items.find((i) => i.id === id)?.title, 'Restore me');
});

test('D08: a file in files-removed is taken back out when a save needs it', async () => {
  const fake = installFakeDrive();
  const root = fake.folder('PettiBox');
  const folder = fake.folder('Sync', root);
  fake.folder('files', folder);
  const removed = fake.folder('files-removed', folder);
  const sha = 'ef'.repeat(32);
  fake.make({ name: sha + '.jpg', parents: [removed] });
  const { driveTransport } = await import('../drive.js?d08-removed');
  const session = await driveTransport.open();
  assert.equal((await session.storedFiles())[sha], undefined);
  assert.ok(await session.restoreFile(sha));
  assert.ok((await session.storedFiles())[sha]);
});
