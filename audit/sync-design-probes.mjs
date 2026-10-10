// Read-only audit probes against production modules; all state/Drive calls are in memory.
// Assertions describe current defects, not desired behavior. Run: node audit/sync-design-probes.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { SyncEngine, memoryStore, shardName } from '../extension/sync/engine.js';
import { encodeShard, decodeShard } from '../extension/sync/codec.js';
import { diff, merge, value, HybridClock, shardOf } from '../extension/sync/model.js';
import { installFakeDrive } from '../extension/test/fakeDrive.js';

const results = [];
const record = (id, detail) => results.push({ id, reproduced: true, detail });
function drive() {
  const files = new Map();
  const d = { files, marker: '', afterList: null, async open() {
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
  } };
  return d;
}

// The actual retry function writes a stale whole metadata object after the edit queue is released.
{
  const d = drive();
  const backing = memoryStore();
  let reached, release;
  const ready = new Promise(r => { reached = r; });
  const gate = new Promise(r => { release = r; });
  let armed = false;
  const store = { get: backing.get, async set(items) {
    if (armed && items.meta?.retryAttempt === 1) { armed = false; reached(); await gate; }
    await backing.set(items);
  } };
  const engine = new SyncEngine(store, d);
  const { id } = await engine.saveLink({ url: 'https://audit.example/a', title: 'Before' });
  await engine.round();
  const source = await readFile(new URL('../extension/background.js', import.meta.url), 'utf8');
  const from = source.indexOf('async function scheduleRetry(');
  const to = source.indexOf('\n}\n', from) + 2;
  const context = vm.createContext({ engine, chrome: { alarms: { async create() {}, async clear() {} } }, ALARM_RETRY: 'retry' });
  vm.runInContext(source.slice(from, to), context);
  armed = true;
  const retry = vm.runInContext('scheduleRetry({ ok: false, retry: true })', context);
  await ready;
  await engine.updateItem(id, { title: 'Local edit after failure' });
  assert.ok((await engine.meta()).dirty.length > 0);
  release(); await retry;
  assert.equal((await engine.meta()).dirty.length, 0);
  await engine.round();
  const peer = new SyncEngine(memoryStore(), d); await peer.round();
  assert.equal((await peer.library()).items[0].title, 'Before');
  record('D01', 'Retry metadata write erased pending-upload status; local title changed, peer still sees Before after another round.');
}

// A reset after the initial marker check does not fence a subsequent upload.
{
  const d = drive(); const a = new SyncEngine(memoryStore(), d);
  const first = await a.saveLink({ url: 'https://audit.example/reset', title: 'A' }); await a.round();
  let id = 'new-record';
  while (shardOf(id) === shardOf(first.id)) id += 'x';
  await a.edit('item', id, l => l, { kind: 'item', id, created: Date.now(),
    fields: { url: 'https://audit.example/new', title: 'B', type: 'LINK', trash: null }, tags: {}, files: {} });
  d.afterList = async () => { d.files.clear(); d.marker = 'reset-2'; d.afterList = null; };
  const out = await a.round();
  assert.equal(out.ok, true); assert.ok(d.files.size > 0);
  assert.equal((await a.round()).needs, 'reset');
  record('D05', 'Reset during a round was followed by a successful upload; only the next round noticed reset.');
}

// Deleting this device's clean shard without a reset marker never makes it dirty again.
{
  const d = drive(); const a = new SyncEngine(memoryStore(), d);
  await a.saveLink({ url: 'https://audit.example/missing', title: 'A' }); await a.round();
  d.files.clear(); const out = await a.round();
  assert.equal(out.ok, true); assert.equal(out.sent, 0); assert.equal(d.files.size, 0);
  record('D11', 'Own shard disappeared but Sync reported success and sent zero files.');
}

// Malformed known fields pass validation, are committed locally, then break downstream consumers.
{
  const d = drive(); const a = new SyncEngine(memoryStore(), d);
  const dev = 'aaaaaaaaaaaaaaaa';
  const wire = { format: 1, device: dev, shard: 99, records: [{ k: 'item', id: 'bad', c: 1,
    vv: { [dev]: 1 }, f: { url: [{ v: 42, t: 999999999999999, d: dev }], title: [{ v: 'Bad', t: 1, d: dev }] } }] };
  const json = JSON.stringify(wire);
  assert.equal(decodeShard(json).records.length, 1);
  d.files.set(shardName(dev, 0), { json, md5: 'bad-wire' });
  assert.equal((await a.round()).ok, true);
  assert.equal((await a.records()).size, 1);
  await assert.rejects(a.savedPage('https://audit.example/a'), /trim/);
  record('D10', 'Decoder accepted numeric URL, out-of-range shard, and head timestamp beyond vv; round reported success but savedPage now throws.');
}

// Older clients report success while ignoring every newer-format shard.
{
  const d = drive(); const a = new SyncEngine(memoryStore(), d);
  d.files.set(shardName('aaaaaaaaaaaaaaaa', 0), { json: '{"format":2}', md5: 'v2' });
  const out = await a.round();
  assert.equal(out.ok, true); assert.equal(out.damaged, 0); assert.equal((await a.meta()).lastError, '');
  record('O01', 'Unsupported protocol file silently skipped; round reported clean success.');
}

// Duplicate files/ folders are not reconciled; files in the second one disappear from discovery.
{
  const fake = installFakeDrive(); const r = fake.folder('PettiBox'); const s = fake.folder('Sync', r);
  fake.folder('files', s); const second = fake.folder('files', s);
  const sha = 'ab'.repeat(32); fake.make({ name: sha + '.jpg', parents: [second] });
  const { driveTransport } = await import('../extension/drive.js?audit-folders');
  const session = await driveTransport.open();
  assert.equal((await session.storedFiles())[sha], undefined);
  record('D06b', 'A valid attachment in the second files/ folder is invisible to storedFiles().');
}

// A concurrent writer adds a file after mergeInto lists children but before it deletes the folder.
{
  const fake = installFakeDrive(); const root1 = fake.folder('PettiBox'); const root2 = fake.folder('PettiBox');
  const fetchDrive = globalThis.fetch; let late;
  globalThis.fetch = async (url, init = {}) => {
    if (init.method === 'DELETE' && new URL(url).pathname.endsWith('/' + root2))
      late = fake.make({ name: 'late-upload', parents: [root2] });
    return fetchDrive(url, init);
  };
  const { driveTransport } = await import('../extension/drive.js?audit-race');
  await driveTransport.open();
  assert.ok(late); assert.equal(fake.files.has(late), false); assert.ok(fake.files.has(root1));
  record('D06a', 'Folder consolidation recursively deleted a file uploaded after the child-list snapshot.');
}

// Model consequence of Android persisting a record before persisting its clock.
// This invokes the shared JS merge rules, not the Android runtime/Room transaction.
{
  const dev = 'aaaaaaaaaaaaaaaa';
  const local = { kind: 'item', id: 'clock', created: 1, fields: { title: 'Before', type: 'LINK' }, tags: {}, files: {} };
  const durable = diff(null, local, { dev, ts: 100 });
  const restartedClock = new HybridClock(0, () => 50);
  const after = diff(durable, { ...local, fields: { ...local.fields, title: 'After restart' } }, { dev, ts: restartedClock.next() });
  assert.equal(value(merge(durable, after), 'title'), null);
  record('D03-model', 'A durable dot at 100 followed by a post-crash dot at 50 loses the title on merge. Android persistence ordering was established by source inspection.');
}

// Review blocks unrelated browser uploads (Android still uploads its outbox in this branch).
{
  const d = drive(); const a = new SyncEngine(memoryStore(), d); const b = new SyncEngine(memoryStore(), d);
  const ids = [];
  for (let i = 0; i < 5; i++) ids.push((await a.saveLink({ url: 'https://audit.example/review/' + i, title: String(i) })).id);
  await a.round(); await b.round();
  for (const id of ids) await b.trash(id);
  await b.round();
  await a.saveLink({ url: 'https://audit.example/unrelated', title: 'Pending local save' });
  assert.equal((await a.round()).needs, 'review');
  assert.ok((await a.meta()).dirty.length > 0);
  await b.round();
  assert.equal((await b.library()).items.some(i => i.title === 'Pending local save'), false);
  record('D12', 'Waiting for removal review prevented an unrelated new browser save from reaching another device.');
}

// The transfer timer is cleared after response headers, before consuming the body.
{
  const fake = installFakeDrive(); fake.folder('PettiBox');
  const fetchDrive = globalThis.fetch;
  let release, entered, signal;
  const gate = new Promise(r => { release = r; });
  const ready = new Promise(r => { entered = r; });
  let first = true;
  globalThis.fetch = async (url, init) => {
    const response = await fetchDrive(url, init);
    if (!first) return response;
    first = false; signal = init.signal;
    return { ok: true, status: 200, async json() { entered(); await gate; return response.json(); } };
  };
  const realTimer = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms, ...args) => realTimer(fn, Math.min(ms, 5), ...args);
  try {
    const { driveTransport } = await import('../extension/drive.js?audit-timeout');
    const opening = driveTransport.open();
    await ready;
    await new Promise(r => realTimer(r, 25));
    assert.equal(signal.aborted, false);
    release(); await opening;
    record('D09', 'With request deadlines shortened to 5ms, a stalled body was still not aborted after 25ms because call() cleared the timer at headers.');
  } finally { globalThis.setTimeout = realTimer; release(); }
}

console.log(JSON.stringify({ baseline: '2ebb9c9afe42f8297741f44362fc3384988d9c82', probes: results }, null, 2));
