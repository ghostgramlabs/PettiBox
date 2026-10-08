// The toolbar popup: the page you're on (save it in one tap, or see where it's
// saved and move it), then search the library. Every change goes to the
// background worker, which owns the library and sync.

const $ = (id) => document.getElementById(id);
const ask = async (type, args = {}) => {
  const res = await chrome.runtime.sendMessage({ type, ...args });
  if (res?.error) throw new Error(res.error);
  return res?.result;
};

let library = { items: [], collections: [] };
let filter = 'all';
let tab = null;
let saved = null; // the save holding this page, if any
let editing = null;

// ── Helpers ──────────────────────────────────────────────────────────────

function ago(ts) {
  if (!ts) return 'not synced yet';
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return 'synced just now';
  if (s < 3600) return `synced ${Math.round(s / 60)} min ago`;
  if (s < 86400) return `synced ${Math.round(s / 3600)} h ago`;
  return `synced ${Math.round(s / 86400)} d ago`;
}
const day = (ts) => new Date(ts).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
function host(url) {
  try { return new URL(url).host.replace(/^www\./, ''); } catch { return url ?? ''; }
}
const colOf = (id) => library.collections.find((c) => c.id === id);
const tagsOf = (text) => text.split(',').map((t) => t.trim()).filter(Boolean);
function el(tag, props = {}, ...kids) {
  const e = Object.assign(document.createElement(tag), props);
  e.append(...kids);
  return e;
}
const TYPE_ICON = { IMAGE: '🖼', PDF: '📄', FILE: '📎', NOTE: '📝', LINK: '🔗', VIDEO: '🎬' };

/** A picture to show for a save: its card picture, or the photo itself when small enough. */
function pictureOf(item) {
  if (item.thumb) return { sha: item.thumb, max: 2_000_000 };
  if (item.type === 'IMAGE' && item.main) return { sha: item.main, max: 3_000_000 };
  return null;
}

const objectUrls = new Map();
async function fileUrl(sha, maxBytes) {
  if (objectUrls.has(sha)) return objectUrls.get(sha);
  const got = await ask('file', { sha, maxBytes }).catch(() => null);
  if (!got?.ok) return null;
  const hit = await (await caches.open('pettibox-files')).match(`https://files.pettibox.invalid/${sha}`);
  if (!hit) return null;
  const url = URL.createObjectURL(await hit.blob());
  objectUrls.set(sha, url);
  return url;
}

// Pictures load only as rows scroll into view.
const lazy = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    lazy.unobserve(e.target);
    const { sha, max } = e.target.dataset;
    fileUrl(sha, Number(max)).then((url) => {
      if (!url) return;
      e.target.replaceChildren(el('img', { src: url, alt: '' }));
    });
  }
}, { rootMargin: '120px' });

function thumbFor(item) {
  const box = el('div', { className: 'thumb', textContent: TYPE_ICON[item.type] ?? '🔗' });
  const pic = pictureOf(item);
  if (pic) {
    box.dataset.sha = pic.sha;
    box.dataset.max = pic.max;
    lazy.observe(box);
  }
  return box;
}

function openFile(file, title) {
  const q = new URLSearchParams({ sha: file.sha, kind: file.kind, title: title ?? '' });
  chrome.tabs.create({ url: chrome.runtime.getURL(`viewer.html?${q}`) });
}

// ── State ────────────────────────────────────────────────────────────────

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
  if (filter === 'trash') {
    // Chips and the page card need the live collections even in this view.
    library.collections = (await ask('library', { trash: false })).collections;
  }
  $('cols').replaceChildren(...library.collections.map((c) => el('option', { value: c.name, label: `${c.emoji} ${c.name}` })));
  render();
}

/** Collections used most recently first, then the rest, up to [n]. */
function recentCollections(n) {
  const order = [];
  for (const item of [...library.items].sort((a, b) => b.updated - a.updated)) {
    if (item.cat && !order.includes(item.cat)) order.push(item.cat);
  }
  for (const c of library.collections) if (!order.includes(c.id)) order.push(c.id);
  return order.slice(0, n).map(colOf).filter(Boolean);
}

// ── The page you're on ───────────────────────────────────────────────────

async function showPage() {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = tab?.url ?? '';
  if (!/^https?:/i.test(url)) { $('page').hidden = true; return; }
  $('page').hidden = false;
  $('page-url').textContent = host(url) + new URL(url).pathname.replace(/\/$/, '');
  $('page-url').title = url;
  $('page-favicon').src = chrome.runtime.getURL(`/_favicon/?pageUrl=${encodeURIComponent(url)}&size=32`);
  saved = await ask('savedPage', { url });
  $('page-title').value = saved?.title ?? tab.title ?? url;
  $('page-new').hidden = !!saved;
  $('page-saved').hidden = !saved || saved.trashed;
  $('page-trash').hidden = !saved?.trashed;

  if (saved) {
    const pic = pictureOf(saved);
    if (pic) fileUrl(pic.sha, pic.max).then((src) => { if (src) $('page-pic').replaceChildren(el('img', { src, className: 'cover', alt: '' })); });
    const col = colOf(saved.cat);
    $('saved-line').textContent = `Saved ${col ? `to ${col.emoji} ${col.name}` : ''} · ${day(saved.created)}`;
    $('move-chips').replaceChildren(
      ...recentCollections(6).map((c) => chip(`${c.emoji} ${c.name}`, () => move(c.id), c.id === saved.cat ? 'current' : '')),
      ...(saved.cat ? [chip('No collection', () => move(null))] : []),
    );
  } else {
    $('save-chips').replaceChildren(
      chip('Save', () => save({}), 'lead'),
      ...recentCollections(5).map((c) => chip(`${c.emoji} ${c.name}`, () => save({ cat: c.id }))),
    );
  }
}

function chip(label, onClick, cls = '') {
  const b = el('button', { textContent: label, className: cls });
  b.addEventListener('click', async () => {
    b.disabled = true;
    try { await onClick(); } finally { b.disabled = false; }
  });
  return b;
}

async function save({ cat = null, colName = '', tags = [], notes = '' }) {
  await ask('save', { url: tab.url, title: $('page-title').value.trim() || tab.title, cat, colName, tags, notes });
  await loadLibrary();
  await showPage();
  await showState();
}

async function move(catId) {
  await ask('update', { id: saved.id, changes: { cat: catId } });
  await loadLibrary();
  await showPage();
}

// ── Library ──────────────────────────────────────────────────────────────

function render() {
  const words = $('search').value.toLowerCase().split(/\s+/).filter(Boolean);
  let items = library.items;
  if (filter === 'fav') items = items.filter((i) => i.fav);
  if (words.length) {
    items = items.filter((i) => {
      const hay = [i.title, i.url, i.notes, i.ocr, ...i.tags, colOf(i.cat)?.name].join(' ').toLowerCase();
      return words.every((w) => hay.includes(w));
    });
  }
  $('results').replaceChildren(...items.slice(0, 200).map(row));
  const empty = $('empty');
  empty.hidden = items.length > 0;
  empty.textContent = filter === 'trash' ? 'Nothing in Recently deleted.'
    : words.length ? 'Nothing matched.' : 'Nothing here yet.';
}

function row(item) {
  const col = colOf(item.cat);
  const meta = [col ? `${col.emoji} ${col.name}` : null, item.url ? host(item.url) : (item.files.length ? `${item.files.length} file${item.files.length > 1 ? 's' : ''}` : 'note'), ...item.tags.map((t) => '#' + t)]
    .filter(Boolean).join(' · ');
  const main = el('div', { className: 'main' },
    el('div', { className: 'title', textContent: (item.fav ? '♥ ' : '') + item.title }),
    el('div', { className: 'meta', textContent: meta }));
  if (item.conflicts.length) main.append(el('div', { className: 'flag', textContent: 'Edited on 2 devices: tap Edit to pick' }));
  main.addEventListener('click', () => {
    if (item.url) chrome.tabs.create({ url: item.url });
    else if (item.files.length) openFile(item.files[0], item.title);
    else openEditor(item);
  });
  const btn = filter === 'trash'
    ? chip('Restore', async () => { await ask('restore', { id: item.id }); await loadLibrary(); })
    : chip('Edit', () => openEditor(item));
  return el('li', {}, thumbFor(item), main, btn);
}

// ── Editor ───────────────────────────────────────────────────────────────

function openEditor(item) {
  editing = item;
  $('edit-title').value = item.title;
  $('edit-url').value = item.url ?? '';
  $('edit-col').value = colOf(item.cat)?.name ?? '';
  $('edit-tags').value = item.tags.join(', ');
  $('edit-note').value = item.notes ?? '';
  $('edit-fav').checked = item.fav;
  $('edit-files').replaceChildren(...item.files.map((f, i) =>
    chip(`${TYPE_ICON[f.kind] ?? '📎'} Open ${f.kind === 'PDF' ? 'PDF' : f.kind === 'IMAGE' ? 'photo' : 'file'}${item.files.length > 1 ? ` ${i + 1}` : ''}`,
      () => openFile(f, item.title))));
  $('conflicts').replaceChildren(...item.conflicts.map((c) => {
    const label = { title: 'Title', notes: 'Note', url: 'Link' }[c.field] ?? c.field;
    return el('div', { className: 'conflict' },
      el('b', { textContent: `${label} edited on 2 devices` }),
      version('Shown now', c.shown, 'Keep', async () => { await ask('keepShown', { id: item.id, field: c.field }); await done(); }),
      ...c.others.map((o) => version('Other', o, 'Use this', async () => { await ask('update', { id: item.id, changes: { [c.field]: o } }); await done(); })));
  }));
  $('sheet').hidden = false;
  $('edit-title').focus();
}

function version(label, text, action, onClick) {
  return el('div', { className: 'v' },
    el('span', { textContent: `${label}: ${text ?? '(empty)'}`, title: text ?? '' }),
    chip(action, onClick));
}

async function done() {
  $('sheet').hidden = true;
  editing = null;
  await loadLibrary();
  await showPage();
  await showState();
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
  await showPage();
  await showState();
});

$('page-title').addEventListener('keydown', async (e) => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  if (saved) { await ask('update', { id: saved.id, changes: { title: $('page-title').value.trim() || saved.title } }); await loadLibrary(); }
  else await save({});
});
$('page-title').addEventListener('blur', async () => {
  const t = $('page-title').value.trim();
  if (saved && t && t !== saved.title) { await ask('update', { id: saved.id, changes: { title: t } }); await loadLibrary(); }
});

$('save-btn').addEventListener('click', () => save({
  colName: $('save-col').value.trim(),
  tags: tagsOf($('save-tags').value),
  notes: $('save-note').value,
}));
$('saved-edit').addEventListener('click', () => saved && openEditor(saved));
$('restore-btn').addEventListener('click', async () => { await ask('restore', { id: saved.id }); await done(); });

$('search').addEventListener('input', render);
for (const c of document.querySelectorAll('.filters .chip')) {
  c.addEventListener('click', async () => {
    document.querySelectorAll('.filters .chip').forEach((x) => x.classList.toggle('on', x === c));
    filter = c.dataset.filter;
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
$('where').addEventListener('click', (e) => { e.preventDefault(); chrome.tabs.create({ url: chrome.runtime.getURL('options.html#where') }); });

async function start() {
  const s = await showState();
  if (!s.signedIn) return;
  await loadLibrary();
  await showPage();
  // Opening the popup syncs, so what's shown catches up within a moment.
  $('sync').classList.add('spin');
  ask('sync')
    .then(async () => { await loadLibrary(); await showPage(); })
    .catch(() => {})
    .finally(async () => { $('sync').classList.remove('spin'); await showState(); });
}

start();
