// Shows the photos, PDFs and files a phone shared for one save, fetched from
// Drive by the background worker (checked against their content hash) and
// cached. A save with several files opens as a gallery: arrows, ← / → keys
// and a strip of thumbnails to move between them.
const params = new URLSearchParams(location.search);
// f=sha:KIND,sha:KIND… (older links pass a single sha and kind).
const files = (params.get('f') ?? `${params.get('sha') ?? ''}:${params.get('kind') ?? 'FILE'}`)
  .split(',').filter(Boolean)
  .map((part) => { const [sha, kind = 'FILE'] = part.split(':'); return { sha, kind }; })
  .filter((f) => f.sha);
let at = Math.min(Math.max(Number(params.get('i')) || 0, 0), Math.max(files.length - 1, 0));
const title = params.get('title') || 'PettiBox';
document.title = title;

const $ = (id) => document.getElementById(id);
const ICON = { IMAGE: '🖼', PDF: '📄', VIDEO: '🎬' };
const NAME = { IMAGE: 'Photo', PDF: 'PDF', VIDEO: 'Video' };

const REASONS = {
  missing: 'This file hasn\'t reached Google Drive yet. Open PettiBox on the phone that saved it while it\'s on Wi-Fi (photos and files are sent on Wi-Fi unless you change that in Settings › Sync between devices), then reopen this.',
  signin: 'Sign in to PettiBox (click its toolbar icon) to see this.',
  damaged: 'This file arrived damaged. Try again after the phone syncs.',
};

/** sha → Promise<{ url, type } | { reason }>, so each file is fetched once. */
const loaded = new Map();
function load(sha) {
  if (!loaded.has(sha)) {
    loaded.set(sha, (async () => {
      try {
        const res = await chrome.runtime.sendMessage({ type: 'file', sha });
        if (res?.error) throw new Error(res.error);
        if (!res?.result?.ok) return { reason: REASONS[res?.result?.reason] ?? 'Couldn\'t open this file.' };
        const hit = await (await caches.open('pettibox-files')).match(`https://files.pettibox.invalid/${sha}`);
        if (!hit) return { reason: 'Couldn\'t open this file.' };
        const blob = await hit.blob();
        return { url: URL.createObjectURL(blob), type: blob.type };
      } catch {
        loaded.delete(sha); // try again next time it's shown
        return { reason: 'Couldn\'t reach Google Drive. Check your connection and try again.' };
      }
    })());
  }
  return loaded.get(sha);
}

const make = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  e.append(...kids);
  return e;
};

async function show() {
  const f = files[at];
  $('count').textContent = files.length > 1 ? `${at + 1} of ${files.length}` : '';
  $('prev').hidden = $('next').hidden = files.length < 2;
  $('prev').disabled = at === 0;
  $('next').disabled = at === files.length - 1;
  for (const [i, t] of [...$('strip').children].entries()) t.classList.toggle('on', i === at);
  $('strip').children[at]?.scrollIntoView({ block: 'nearest', inline: 'center' });
  if (!f) { $('box').replaceChildren(make('p', { textContent: 'This save has no files.' })); return; }

  $('box').replaceChildren(make('p', { textContent: 'Loading…' }));
  const shownFor = at;
  const got = await load(f.sha);
  if (shownFor !== at) return; // moved on meanwhile
  if (got.reason) { $('box').replaceChildren(make('p', { textContent: got.reason })); return; }
  const open = make('a', { href: got.url, target: '_blank', className: 'open', textContent: 'Open in a new tab' });
  if (f.kind === 'IMAGE' || got.type.startsWith('image/')) {
    $('box').replaceChildren(make('img', { src: got.url, alt: `${title}, ${NAME.IMAGE.toLowerCase()} ${at + 1}` }));
  } else if (f.kind === 'PDF' || got.type === 'application/pdf') {
    $('box').replaceChildren(make('iframe', { src: got.url, title: `${title}, PDF` }), open);
  } else if (f.kind === 'VIDEO' || got.type.startsWith('video/')) {
    $('box').replaceChildren(make('video', { src: got.url, controls: true }));
  } else {
    $('box').replaceChildren(make('p', { textContent: '📎 This file can\'t be shown here.' }), open);
  }
}

function go(i) {
  if (i < 0 || i >= files.length || i === at) return;
  at = i;
  show();
}

// Thumbnail strip: photos load their own picture, other files show an icon.
if (files.length > 1) {
  $('strip').hidden = false;
  $('strip').replaceChildren(...files.map((f, i) => {
    const b = make('button', { className: 'tile', title: `${NAME[f.kind] ?? 'File'} ${i + 1}`, textContent: ICON[f.kind] ?? '📎' });
    b.addEventListener('click', () => go(i));
    if (f.kind === 'IMAGE') {
      load(f.sha).then((got) => { if (got.url) b.replaceChildren(make('img', { src: got.url, alt: '' })); });
    }
    return b;
  }));
}
$('title').textContent = title;
$('prev').addEventListener('click', () => go(at - 1));
$('next').addEventListener('click', () => go(at + 1));
document.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowLeft') go(at - 1);
  if (e.key === 'ArrowRight') go(at + 1);
});
show();
