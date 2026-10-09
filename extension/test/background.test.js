import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDrive } from './fakeDrive.js';

test('settings and sign-out wait for sync; wiping cannot be undone by a pending round', async () => {
  const drive = installFakeDrive();
  const data = {};
  const alarms = new Map();
  const event = () => ({ addListener() {} });
  let receive;
  Object.assign(globalThis.chrome, {
    storage: { local: {
      async get(keys) { return structuredClone(Object.fromEntries(keys.filter((k) => k in data).map((k) => [k, data[k]]))); },
      async set(items) { Object.assign(data, structuredClone(items)); },
      async clear() { for (const k of Object.keys(data)) delete data[k]; },
    } },
    alarms: { async clear(k) { alarms.delete(k); }, async create(k, v) { alarms.set(k, v); }, onAlarm: event() },
    action: { async setBadgeText() {}, async setBadgeBackgroundColor() {} },
    runtime: { onInstalled: event(), onStartup: event(), onMessage: { addListener(fn) { receive = fn; } } },
    contextMenus: { create() {}, onClicked: event() },
    commands: { onCommand: event() },
  });
  globalThis.caches = { async delete() { return true; } };
  await import('../background.js');
  const ask = (type, args = {}) => new Promise((resolve, reject) => receive({ type, ...args }, {}, (r) => r.error ? reject(Error(r.error)) : resolve(r.result)));
  await ask('save', { url: 'https://example.com/a', title: 'A' });
  const fetchDrive = globalThis.fetch;
  const pauseRound = () => {
    let release, entered;
    const gate = new Promise((r) => { release = r; });
    const ready = new Promise((r) => { entered = r; });
    globalThis.fetch = async (url, init) => {
      if (String(url).includes('/about?')) { entered(); await gate; }
      return fetchDrive(url, init);
    };
    return { release, ready };
  };
  let pause = pauseRound();
  let round = ask('sync');
  await pause.ready;
  const rename = ask('setName', { name: 'My browser' });
  const interval = ask('setInterval', { interval: 'MINUTES_15' });
  pause.release();
  await Promise.all([round, rename, interval]);
  assert.equal(data.meta.deviceName, 'My browser');
  assert.equal(data.meta.interval, 'MINUTES_15');
  assert.ok(data.meta.dirty.length > 0, 'rename remains pending for upload');

  pause = pauseRound();
  round = ask('sync');
  await pause.ready;
  const wipe = ask('signOut', { wipe: true });
  pause.release();
  await Promise.all([round, wipe]);
  assert.equal((await ask('library')).items.length, 0);
  assert.equal((await ask('state')).signedIn, false);
  assert.equal((await ask('sync')).needs, 'signin');
  assert.equal((await ask('library')).items.length, 0);
  assert.equal(alarms.size, 0);
  assert.ok(drive.files.size > 0, 'sign-out never removes the remote copy');
  let networkCalls = 0;
  globalThis.fetch = async () => { networkCalls++; throw Error('Drive offline'); };
  assert.equal((await ask('signIn')).ok, true);
  assert.equal(networkCalls, 0, 'sign-in response must not wait for Drive');
  assert.equal((await ask('state')).signedIn, true);
  assert.ok(alarms.has('sync-soon'), 'sync survives the OAuth popup closing');
  chrome.identity.getAuthToken = async () => { throw Error('OAuth client rejected'); };
  await assert.rejects(ask('signIn'), /OAuth client rejected/);

});
