// drive.js against an in-memory Google Drive: everything goes under
// My Drive/PettiBox/Sync, old folders move in, and duplicates merge.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDrive } from './fakeDrive.js';

let drive;
let transport;

beforeEach(async () => {
  drive = installFakeDrive();
  // A fresh copy of the module each time, so its folder cache starts empty.
  ({ driveTransport: transport } = await import(`../drive.js?run=${Math.random()}`));
});

test('a fresh Drive gets My Drive/PettiBox/Sync', async () => {
  const s = await transport.open();
  await s.write('d-0123456789abcdef-0.json.gz', '{"format":1,"device":"0123456789abcdef","records":[]}', null);
  assert.deepEqual(drive.tree(), ['PettiBox', 'PettiBox/Sync', 'PettiBox/Sync/d-0123456789abcdef-0.json.gz']);
  const [f] = await s.list();
  assert.equal(await s.read(f), '{"format":1,"device":"0123456789abcdef","records":[]}');
});

test('an old top-level "PettiBox Sync" is moved in, contents intact', async () => {
  const old = drive.folder('PettiBox Sync');
  drive.make({ name: 'd-aaaaaaaaaaaaaaaa-1.json.gz', parents: [old] }, new Uint8Array([1, 2, 3]));
  const s = await transport.open();
  assert.deepEqual(drive.tree(), ['PettiBox', 'PettiBox/Sync', 'PettiBox/Sync/d-aaaaaaaaaaaaaaaa-1.json.gz']);
  assert.equal((await s.list()).length, 1);
});

test('folders made twice at the same moment are merged into the oldest', async () => {
  const rootA = drive.folder('PettiBox');
  const rootB = drive.folder('PettiBox');
  const syncA = drive.folder('Sync', rootA);
  const syncB = drive.folder('Sync', rootB);
  drive.make({ name: 'd-aaaaaaaaaaaaaaaa-0.json.gz', parents: [syncA] });
  drive.make({ name: 'd-bbbbbbbbbbbbbbbb-0.json.gz', parents: [syncB] });
  const s = await transport.open();
  const names = (await s.list()).map((f) => f.name).sort();
  assert.deepEqual(names, ['d-aaaaaaaaaaaaaaaa-0.json.gz', 'd-bbbbbbbbbbbbbbbb-0.json.gz']);
  assert.deepEqual(drive.tree(), [
    'PettiBox', 'PettiBox/Sync', 'PettiBox/Sync/d-aaaaaaaaaaaaaaaa-0.json.gz', 'PettiBox/Sync/d-bbbbbbbbbbbbbbbb-0.json.gz',
  ]);
});

test('an old folder made again by an older app version is folded in', async () => {
  await transport.open(); // PettiBox/Sync exists now
  const old = drive.folder('PettiBox Sync');
  drive.make({ name: 'd-cccccccccccccccc-2.json.gz', parents: [old] });
  ({ driveTransport: transport } = await import(`../drive.js?run=${Math.random()}`));
  const s = await transport.open();
  assert.deepEqual((await s.list()).map((f) => f.name), ['d-cccccccccccccccc-2.json.gz']);
  assert.ok(!drive.tree().includes('PettiBox Sync'));
});

test('a device file round-trips through gzip and replace-in-place', async () => {
  const s = await transport.open();
  await s.write('d-0123456789abcdef-3.json.gz', 'first', null);
  const [f] = await s.list();
  await s.write('d-0123456789abcdef-3.json.gz', 'second', f.id);
  const files = await s.list();
  assert.equal(files.length, 1);
  assert.equal(await s.read(files[0]), 'second');
});
