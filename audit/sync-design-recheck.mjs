// Recheck evidence for residual audit findings. These assertions describe defects,
// not release acceptance tests. No network, credentials, or user data are used.
import assert from 'node:assert/strict';
import { installFakeDrive } from '../extension/test/fakeDrive.js';
import { decodeShard, encodeShard } from '../extension/sync/codec.js';
import { SyncEngine, memoryStore, shardName } from '../extension/sync/engine.js';
import { shardOf } from '../extension/sync/model.js';

const findings = [];
const bytes = (s) => new TextEncoder().encode(s);

// Real Chrome marker reader; the PATCH target models Android resetEverything's
// first matching marker. This does not execute the Android reset implementation.
{
  const fake = installFakeDrive();
  const root = fake.folder('PettiBox');
  const folder = fake.folder('Sync', root);
  const marker = (resetAt) => JSON.stringify({ format: 1, resetAt, by: 'aaaaaaaaaaaaaaaa' });
  const first = fake.make({ name: 'pettibox-sync-reset.json', parents: [folder] }, bytes(marker(1)));
  fake.make({ name: 'pettibox-sync-reset.json', parents: [folder] }, bytes(marker(2)));
  const { driveTransport } = await import('../extension/drive.js?recheck-reset');
  const session = await driveTransport.open();
  // First subsequent reset updates only the first marker; marker 2 wins.
  await fetch('https://www.googleapis.com/upload/drive/v3/files/' + first + '?uploadType=media',
    { method: 'PATCH', body: marker(3) });
  const before = await session.resetMarker();
  // Another reset again updates only the first; effective generation is unchanged.
  await fetch('https://www.googleapis.com/upload/drive/v3/files/' + first + '?uploadType=media',
    { method: 'PATCH', body: marker(4) });
  const after = await session.resetMarker();
  assert.equal(before, marker(2));
  assert.equal(after, before);
  findings.push({ id: 'D05', evidence: 'Production Chrome reader plus modeled Android write selection',
    result: 'Updating one of two reset markers can leave the effective epoch unchanged across a later reset.' });
}

{
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
  const { driveTransport } = await import('../extension/drive.js?recheck-folders');
  const session = await driveTransport.open();
  const current = await session.storedFiles();
  assert.ok(late && fake.files.has(late));
  assert.equal(current[sha], undefined);
  const next = await driveTransport.open();
  assert.equal((await next.storedFiles())[sha], undefined);
  assert.equal(fake.files.get(duplicate).trashed, true);
  findings.push({ id: 'D06', evidence: 'Production Chrome transport with fake Drive',
    result: 'A late blob survives physically but is stranded under the trashed duplicate and remains undiscoverable next round.' });
}

{
  const device = 'bbbbbbbbbbbbbbbb';
  const id = 'wrong-placement';
  const wrongShard = (shardOf(id) + 1) % 16;
  const record = { kind: 'item', id, created: 1, vv: { [device]: 1 },
    fields: { title: [{ value: 'Wrong shard', dot: { dev: device, ts: 1 } }],
      remind: [{ value: Number.MAX_SAFE_INTEGER + 1, dot: { dev: device, ts: 1 } }] },
    tags: {}, files: {}, gone: null };
  const json = encodeShard(device, 'Other', wrongShard, 1, [record]);
  const decoded = decodeShard(json);
  assert.equal(decoded.records[0].id, id);
  assert.equal(Number.isSafeInteger(decoded.records[0].fields.remind[0].value), false);
  const engine = new SyncEngine(memoryStore(), { async open() { return {
    accountEmail: async () => 'recheck@example.com', resetMarker: async () => '',
    list: async () => [{ id: 'remote', name: shardName(device, wrongShard), md5: 'one' }],
    read: async () => json, write: async () => {},
  }; } });
  const outcome = await engine.round();
  assert.equal(outcome.ok, true);
  assert.equal(outcome.damaged, 0);
  assert.equal((await engine.records()).size, 1);
  findings.push({ id: 'D10', evidence: 'Production Chrome codec and engine',
    result: 'A record in the wrong shard with an unsafe integer reminder is accepted and persisted.' });
}

console.log(JSON.stringify({ reviewedRevision: '17b36760ee3fc2c66654e2c42308a77595567d5e',
  type: 'Residual-defect probes, not acceptance tests', findings }, null, 2));
