// The toolbar popup: save this tab, search the library, edit a save. Every
// change goes to the background worker, which owns the library and sync.

const $ = (id) => document.getElementById(id);
const ask = async (type, args = {}) => {
  const res = await chrome.runtime.sendMessage({ type, ...args });
  if (res?.error) throw new Error(res.error);
  return res?.result;
};

let library = { items: [], collections: [] };
let filter = 'all';
let tab = null;
let editing = null;

function ago(ts) {
  if (!ts) return 'not synced yet';
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return 'synced just now';
  if (s < 3600) return `synced ${Math.round(s / 60)} min ago`;
  if (s < 86400) return `synced ${Math.round(s / 3600)} h ago`;
  return `synced ${Math.round(s / 86400)} d ago`;
}

function host(url) {
  try { return new URL(url).host.replace(/^www\./, ''); } catch { return url ?? ''; }
}

const colName = (id) => library.collections.find((c) => c.id === id);
const tagsOf = (text) => text.split(',').map((t) => t.trim()).filter(Boolean);

async function showState() {
  const s = await ask('state');
  $('signin').hidden = s.signedIn;
  $('app').hidden = !s.signedIn;
  $('account').textContent = s.account || '';
  const status = $('status');
  status.classList.toggle('err', !!s.lastError);
  status.textContent = s.lastError ? s.lastError : (s.waiting ? 'changes waiting · ' : '') + ago(s.lastSyncAt);
  status.title = status.textContent;
  return s;
}

async function loadLibrary() {
  library = await ask('library', { trash: filter === 'trash' });
  $('cols').replaceChildren(...library.collections.map((c) => {
    const o = document.createElement('option');
    o.value = c.name;
    o.label = `${c.emoji} ${c.name}`;
    return o;
  }));
  render();
}

function render() {
  const words = $('search').value.toLowerCase().split(/\s+/).filter(Boolean);
  let items = library.items;
  if (filter === 'fav') items = items.filter((i) => i.fav);
  if (words.length) {
    items = items.filter((i) => {
      const hay = [i.title, i.url, i.notes, i.ocr, ...i.tags, colName(i.cat)?.name].join(' ').toLowerCase();
      return words.every((w) => hay.includes(w));
    });
  }
  const list = $('results');
  list.replaceChildren(...items.slice(0, 200).map(row));
  const empty = $('empty');
  empty.hidden = items.length > 0;
  empty.textContent = filter === 'trash' ? 'Nothing in Recently deleted.'
    : words.length ? 'Nothing matched.' : 'Nothing here yet. Save this tab above.';
}

function row(item) {
  const li = document.createElement('li');
  const main = document.createElement('div');
  main.className = 'main';
  const title = document.createElement('div');
  title.className = 'title';
  title.textContent = (item.fav ? '♥ ' : '') + item.title;
  const meta = document.createElement('div');
  meta.className = 'meta';
  const col = colName(item.cat);
  meta.textContent = [col ? `${col.emoji} ${col.name}` : null, item.url ? host(item.url) : 'note', ...item.tags.map((t) => '#' + t)]
    .filter(Boolean).join(' · ');
  main.append(title, meta);
  if (item.conflicts.length) {
    const flag = document.createElement('div');
    flag.className = 'flag';
    flag.textContent = 'Edited on 2 devices: tap Edit to pick';
    main.append(flag);
  }
  main.addEventListener('click', () => {
    if (item.url) chrome.tabs.create({ url: item.url });
    else openEditor(item);
  });
  const btn = document.createElement('button');
  if (filter === 'trash') {
    btn.textContent = 'Restore';
    btn.addEventListener('click', async () => { await ask('restore', { id: item.id }); await loadLibrary(); });
  } else {
    btn.textContent = 'Edit';
    btn.addEventListener('click', () => openEditor(item));
  }
  li.append(main, btn);
  return li;
}

function openEditor(item) {
  editing = item;
  $('edit-title').value = item.title;
  $('edit-url').value = item.url ?? '';
  $('edit-col').value = colName(item.cat)?.name ?? '';
  $('edit-tags').value = item.tags.join(', ');
  $('edit-note').value = item.notes ?? '';
  $('edit-fav').checked = item.fav;
  const box = $('conflicts');
  box.replaceChildren(...item.conflicts.map((c) => {
    const d = document.createElement('div');
    d.className = 'conflict';
    const label = { title: 'Title', notes: 'Note', url: 'Link' }[c.field] ?? c.field;
    d.append(Object.assign(document.createElement('b'), { textContent: `${label} edited on 2 devices` }));
    const keep = version('Shown now', c.shown, 'Keep', async () => { await ask('keepShown', { id: item.id, field: c.field }); done(); });
    d.append(keep, ...c.others.map((o) => version('Other', o, 'Use this', async () => {
      await ask('update', { id: item.id, changes: { [c.field]: o } });
      done();
    })));
    return d;
  }));
  $('sheet').hidden = false;
  $('edit-title').focus();
}

function version(label, text, action, onClick) {
  const v = document.createElement('div');
  v.className = 'v';
  const span = document.createElement('span');
  span.textContent = `${label}: ${text ?? '(empty)'}`;
  span.title = text ?? '';
  const b = document.createElement('button');
  b.textContent = action;
  b.addEventListener('click', onClick);
  v.append(span, b);
  return v;
}

async function done() {
  $('sheet').hidden = true;
  editing = null;
  await loadLibrary();
  await showState();
}

async function prepareSave() {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = tab?.url ?? '';
  const savable = /^https?:/i.test(url);
  $('save-card').hidden = !savable;
  if (!savable) return;
  $('save-title').value = tab.title || url;
  $('save-url').textContent = url;
  const existing = await ask('findByUrl', { url });
  $('saved-note').hidden = !existing;
  $('save-btn').textContent = existing ? 'Saved' : 'Save to PettiBox';
  $('save-btn').disabled = !!existing;
  $('saved-edit').onclick = (e) => {
    e.preventDefault();
    const item = library.items.find((i) => i.id === existing?.id);
    if (item) openEditor(item);
  };
}

// ── Wiring ───────────────────────────────────────────────────────────────

$('signin-btn').addEventListener('click', async () => {
  $('signin-btn').disabled = true;
  try {
    const out = await ask('signIn');
    if (!out?.ok) $('signin-btn').textContent = 'Sign-in was cancelled. Try again';
    await start();
  } finally {
    $('signin-btn').disabled = false;
  }
});

$('sync').addEventListener('click', async () => {
  $('sync').classList.add('spin');
  try { await ask('sync'); } finally { $('sync').classList.remove('spin'); }
  await loadLibrary();
  await showState();
});

$('save-btn').addEventListener('click', async () => {
  $('save-btn').disabled = true;
  await ask('save', {
    url: tab.url,
    title: $('save-title').value,
    colName: $('save-col').value.trim(),
    tags: tagsOf($('save-tags').value),
    notes: $('save-note').value,
  });
  $('save-btn').textContent = 'Saved ✓';
  $('saved-note').hidden = false;
  await loadLibrary();
  await showState();
});

$('search').addEventListener('input', render);
for (const chip of document.querySelectorAll('.chip')) {
  chip.addEventListener('click', async () => {
    document.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', c === chip));
    filter = chip.dataset.filter;
    await loadLibrary();
  });
}

$('edit-cancel').addEventListener('click', () => { $('sheet').hidden = true; editing = null; });
$('edit-save').addEventListener('click', async () => {
  if (!editing) return;
  await ask('update', {
    id: editing.id,
    colName: $('edit-col').value.trim(),
    changes: {
      title: $('edit-title').value.trim() || 'Untitled',
      url: $('edit-url').value.trim(),
      notes: $('edit-note').value,
      fav: $('edit-fav').checked,
      tags: tagsOf($('edit-tags').value),
    },
  });
  await done();
});
$('edit-delete').addEventListener('click', async () => {
  if (!editing) return;
  await ask('trash', { id: editing.id });
  await done();
});
$('options').addEventListener('click', (e) => { e.preventDefault(); chrome.runtime.openOptionsPage(); });

async function start() {
  const s = await showState();
  if (!s.signedIn) return;
  await loadLibrary();
  await prepareSave();
  $('search').focus();
}

start();
