import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

test('a page card can refresh after its favicon was replaced by a saved picture', async () => {
  const elements = new Map();
  const make = () => ({
    children: [], dataset: {}, classList: { add() {}, remove() {}, toggle() {} },
    append(...kids) { this.children.push(...kids); },
    replaceChildren(...kids) {
      for (const old of this.children) if (old.id) elements.delete(old.id);
      this.children = kids;
      for (const kid of kids) if (kid.id) elements.set(kid.id, kid);
    },
    addEventListener() {},
  });
  const html = await readFile(new URL('../popup.html', import.meta.url), 'utf8');
  for (const [, id] of html.matchAll(/id="([^"]+)"/g)) elements.set(id, make());
  elements.get('page-favicon').id = 'page-favicon';
  elements.get('page-pic').children = [elements.get('page-favicon')];
  const context = vm.createContext({
    document: { getElementById: (id) => elements.get(id) ?? null, createElement: make, querySelectorAll: () => [] },
    IntersectionObserver: class { observe() {} unobserve() {} },
    URL: class extends URL { static createObjectURL() { return 'blob:picture'; } },
    URLSearchParams,
    caches: { async open() { return { async match() { return { async blob() { return new Blob(['image']); } }; } }; } },
    chrome: {
      runtime: {
        getURL: (path) => path,
        async sendMessage(msg) {
          if (msg.type === 'savedPage') return { result: { id: 'save', title: 'Photo', thumb: 'aa', created: 1 } };
          if (msg.type === 'file') return { result: { ok: true } };
          throw Error('Unexpected request: ' + msg.type);
        },
      },
      tabs: { async query() { return [{ url: 'https://example.com', title: 'Page' }]; } },
    },
  });
  const source = await readFile(new URL('../popup.js', import.meta.url), 'utf8');
  vm.runInContext(source.slice(0, source.lastIndexOf('start();')), context);
  await vm.runInContext('showPage()', context);
  await new Promise((r) => setImmediate(r));
  assert.equal(elements.get('page-pic').children[0].src, 'blob:picture');
  assert.equal(elements.has('page-favicon'), false);
  await vm.runInContext('showPage()', context);
  await new Promise((r) => setImmediate(r));
  assert.equal(elements.get('page-pic').children[0].src, 'blob:picture');
});


async function signInUI(sendMessage) {
  const elements = new Map();
  const make = () => ({
    value: '', children: [], dataset: {}, listeners: {}, hidden: false,
    classList: { add() {}, remove() {}, toggle() {} },
    append(...kids) { this.children.push(...kids); },
    replaceChildren(...kids) { this.children = kids; },
    addEventListener(type, fn) { this.listeners[type] = fn; },
    focus() {},
  });
  const html = await readFile(new URL('../popup.html', import.meta.url), 'utf8');
  for (const [, id] of html.matchAll(/id="([^"]+)"/g)) elements.set(id, make());
  const context = vm.createContext({
    document: { getElementById: (id) => elements.get(id), createElement: make, querySelectorAll: () => [] },
    IntersectionObserver: class { observe() {} unobserve() {} },
    chrome: { runtime: { sendMessage }, tabs: { async query() { return []; } } },
  });
  const source = await readFile(new URL('../popup.js', import.meta.url), 'utf8');
  vm.runInContext(source.slice(0, source.lastIndexOf('start();')), context);
  elements.__run = (code) => vm.runInContext(code, context);
  return elements;
}

test('sign-in updates the popup before slow sync completes, then shows sync errors', async () => {
  let finish;
  const sync = new Promise((r) => { finish = r; });
  let finished = false;
  const elements = await signInUI(async ({ type }) => {
    if (type === 'signIn') return { result: { ok: true } };
    if (type === 'state') return { result: { signedIn: true, lastError: finished ? 'Drive unavailable' : '' } };
    if (type === 'library') return { result: { items: [], collections: [] } };
    if (type === 'sync') { await sync; finished = true; return { result: { ok: false } }; }
    throw Error(type);
  });
  await elements.get('signin-btn').listeners.click();
  assert.equal(elements.get('signin').hidden, true);
  assert.equal(elements.get('account').textContent, 'Signed in');
  assert.equal(elements.get('status').textContent, 'Syncing with Google Drive…');
  assert.equal(elements.get('signin-btn').disabled, false);
  finish();
  await new Promise((r) => setImmediate(r));
  assert.equal(elements.get('error').hidden, false);
  assert.equal(elements.get('error').textContent, 'Drive unavailable');
  assert.equal(elements.get('sync').disabled, false);
});

test('authentication errors are visible and the sign-in button can be retried', async () => {
  const elements = await signInUI(async () => ({ error: 'OAuth client rejected' }));
  await elements.get('signin-btn').listeners.click();
  assert.equal(elements.get('error').hidden, false);
  assert.equal(elements.get('error').textContent, 'OAuth client rejected');
  assert.equal(elements.get('signin-btn').disabled, false);
});

test('the editor sends only the fields that were changed', async () => {
  const sent = [];
  const elements = await signInUI(async (msg) => {
    sent.push(msg);
    if (msg.type === 'library') return { result: { items: [], collections: [] } };
    if (msg.type === 'state') return { result: { signedIn: true } };
    if (msg.type === 'update') return { result: true };
    return { result: null };
  });
  // Open the editor on a save, then change only the note.
  const item = { id: 's1', title: 'T', url: 'https://e.com', notes: 'old', fav: false, tags: ['a'], cat: null, files: [], conflicts: [] };
  await elements.__run(`openEditor(${JSON.stringify(item)})`);
  elements.get('edit-note').value = 'new note';
  await elements.get('edit-save').listeners.click();
  const update = sent.find((m) => m.type === 'update');
  assert.equal(JSON.stringify(update.changes), JSON.stringify({ notes: 'new note' }));
  assert.equal('colName' in update, false);
});
