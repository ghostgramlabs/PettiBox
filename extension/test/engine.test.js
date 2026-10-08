import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { SyncEngine, memoryStore, deviceOf, shardName, urlKey } from '../sync/engine.js';
import { encodeShard } from '../sync/codec.js';
import { diff, KIND_COLLECTION, F } from '../sync/model.js';
import { gzip, gunzip, md5 } from '../sync/bytes.js';

/** The Drive folder: name -> { id, json, md5 }. */
function fakeDrive() {
  const files = new Map();
  const drive = {
    files,
    corrupt: null,
    async open() {
      return {
        list: async () => [...files].map(([name, f]) => ({ id: f.id, name, md5: f.md5, modifiedAt: 0 })),
        read: async (file) => {
          if (file.name === drive.corrupt) return '{"format":1,"device":"broken"';
          return files.get(file.name).json;
        },
        write: async (name, json, existingId) => {
          files.set(name, { id: files.get(name)?.id ?? String(Math.random()), json, md5: createHash('md5').update(json).digest('hex') });
        },
        accountEmail: async () => 'me@example.com',
      };
    },
  };
  return drive;
}

const browser = (drive) => new SyncEngine(memoryStore(), drive);

test('a saved tab reaches the other browser, and edits on both survive', async () => {
  const drive = fakeDrive();
  const a = browser(drive);
  const b = browser(drive);
  const { id } = await a.saveLink({ url: 'https://example.com/sourdough', title: 'Sourdough', tags: ['Bread'] });
  assert.equal((await a.round()).ok, true);
  assert.equal((await b.round()).ok, true);
  assert.deepEqual((await b.library()).items.map((i) => i.title), ['Sourdough']);

  await a.updateItem(id, { title: 'Sourdough basics' });
  await b.updateItem(id, { notes: 'autolyse matters', tags: ['Bread', 'Science'] });
  for (let i = 0; i < 2; i++) { await a.round(); await b.round(); }
  for (const e of [a, b]) {
    const item = (await e.library()).items[0];
    assert.equal(item.title, 'Sourdough basics');
    assert.equal(item.notes, 'autolyse matters');
    assert.deepEqual(item.tags.sort(), ['Bread', 'Science']);
  }
});

test('saving the same link twice keeps one save, even on a double click', async () => {
  const a = browser(fakeDrive());
  const [one, two] = await Promise.all([
    a.saveLink({ url: 'https://Example.com/x', title: 'X' }),
    a.saveLink({ url: 'https://example.COM/x', title: 'X again' }),
  ]);
  assert.equal(one.id, two.id);
  assert.equal(two.existed, true);
  assert.equal((await a.library()).items.length, 1);
});

test('an edit made while a round runs is not lost', async () => {
  const drive = fakeDrive();
  const a = browser(drive);
  const { id } = await a.saveLink({ url: 'https://example.com/a', title: 'A' });
  const round = a.round();
  const editing = a.updateItem(id, { title: 'edited during sync' });
  await Promise.all([round, editing]);
  await a.round();
  assert.equal((await a.library()).items[0].title, 'edited during sync');
  const b = browser(drive);
  await b.round();
  assert.equal((await b.library()).items[0].title, 'edited during sync');
});

test('a delete goes to Recently deleted everywhere and can be undone', async () => {
  const drive = fakeDrive();
  const a = browser(drive);
  const b = browser(drive);
  const { id } = await a.saveLink({ url: 'https://example.com/a', title: 'A' });
  await a.round(); await b.round();
  await b.trash(id);
  await b.round(); await a.round();
  assert.equal((await a.library()).items.length, 0);
  assert.equal((await a.library({ trash: true })).items.length, 1);
  await a.restore(id);
  await a.round(); await b.round();
  assert.equal((await b.library()).items.length, 1);
});

test('locked collections and their saves never show in the browser', async () => {
  const drive = fakeDrive();
  const phone = browser(drive); // stands in for the phone writing a locked collection
  const col = diff(null, {
    kind: KIND_COLLECTION, id: 'user_secret', created: 1,
    fields: { [F.NAME]: 'Secret', [F.EMOJI]: '🔒', [F.COLOR]: 1, [F.ORDER]: 0, [F.PARENT]: null, [F.USER]: true, [F.LOCKED]: true },
    tags: {}, files: {},
  }, { dev: 'aaaaaaaaaaaaaaaa', ts: 5 });
  await drive.open().then((s) => s.write(shardName('aaaaaaaaaaaaaaaa', 0), encodeShard('aaaaaaaaaaaaaaaa', 'Phone', 0, 5, [col]), null));
  await phone.round();
  const { id } = await phone.saveLink({ url: 'https://example.com/private', title: 'Private', cat: 'user_secret' });
  await phone.saveLink({ url: 'https://example.com/open', title: 'Open' });
  const lib = await phone.library();
  assert.deepEqual(lib.items.map((i) => i.title), ['Open']);
  assert.equal(lib.collections.find((c) => c.id === 'user_secret'), undefined);
  assert.ok(id);
});

test('a damaged file is skipped whole and picked up once it is fixed', async () => {
  const drive = fakeDrive();
  const a = browser(drive);
  await a.saveLink({ url: 'https://example.com/a', title: 'A' });
  await a.round();
  const b = browser(drive);
  drive.corrupt = [...drive.files.keys()][0];
  const out = await b.round();
  assert.equal(out.ok, true);
  assert.equal(out.damaged, 1);
  assert.equal((await b.library()).items.length, 0);
  drive.corrupt = null;
  await b.round();
  assert.equal((await b.library()).items.length, 1);
});

test('device file names and the link rule match the app', () => {
  assert.equal(shardName('0123456789abcdef', 10), 'd-0123456789abcdef-a.json.gz');
  assert.equal(deviceOf('d-0123456789abcdef-a.json.gz'), '0123456789abcdef');
  assert.equal(deviceOf('pettibox-auto-backup.zip'), null);
  assert.equal(urlKey(' HTTPS://Example.COM/Path?Q=1 '), 'https://example.com/Path?Q=1');
});

test('md5 and gzip agree with Node', async () => {
  for (const n of [0, 1, 55, 56, 63, 64, 65, 1000, 100_000]) {
    const bytes = new Uint8Array(randomBytes(n));
    assert.equal(md5(bytes), createHash('md5').update(bytes).digest('hex'), `md5 of ${n} bytes`);
  }
  const text = JSON.stringify({ hello: 'wörld', n: [1, 2, 3] }).repeat(50);
  assert.equal(await gunzip(await gzip(text)), text);
});

test('the page card finds a saved page, with its pictures and files, and its trash state', async () => {
  const a = browser(fakeDrive());
  const { id } = await a.saveLink({ url: 'https://example.com/pic', title: 'Pic' });
  // A phone's card picture and photo arrive as fields on the save.
  await a.edit('item', id, (l) => { l.fields.thumb = 'ab'.repeat(32); l.fields.type = 'IMAGE'; l.fields.main = 'cd'.repeat(32); return l; });
  const seen = await a.savedPage('https://EXAMPLE.com/pic');
  assert.equal(seen.id, id);
  assert.equal(seen.thumb, 'ab'.repeat(32));
  assert.deepEqual(seen.files.map((f) => f.sha), ['cd'.repeat(32)]);
  assert.equal(seen.trashed, false);
  await a.trash(id);
  assert.equal((await a.savedPage('https://example.com/pic')).trashed, true);
  assert.equal(await a.savedPage('https://example.com/other'), null);
});

test('synced data deleted from Drive stops this browser, keeps its library, and signing in again starts fresh', async () => {
  const drive = fakeDrive();
  drive.marker = null;
  const open = drive.open.bind(drive);
  drive.open = async () => ({ ...(await open()), resetMarker: async () => drive.marker });
  const a = browser(drive);
  await a.saveLink({ url: 'https://example.com/a', title: 'A' });
  assert.equal((await a.round()).ok, true);
  drive.files.clear();
  drive.marker = '{"resetAt":1}';
  const out = await a.round();
  assert.equal(out.ok, false);
  assert.equal(out.needs, 'reset');
  assert.equal(drive.files.size, 0); // didn't upload it all again
  assert.equal((await a.library()).items.length, 1); // still here
  assert.equal((await a.round()).needs, 'reset'); // stays stopped
  await a.restart(); // what signing in again does
  assert.equal((await a.round()).ok, true);
  assert.ok(drive.files.size > 0);
});
