const $ = (id) => document.getElementById(id);
const ask = async (type, args = {}) => {
  const res = await chrome.runtime.sendMessage({ type, ...args });
  if (res?.error) throw new Error(res.error);
  return res?.result;
};

function when(ts) {
  return ts ? new Date(ts).toLocaleString() : 'never';
}

async function refresh() {
  const s = await ask('state');
  $('account').textContent = s.signedIn ? (s.account || 'Signed in') : 'Not signed in';
  $('signin').hidden = s.signedIn;
  $('signout').hidden = !s.signedIn;
  $('last').textContent = `Last synced: ${when(s.lastSyncAt)}${s.waiting ? ' · changes waiting to upload' : ''}`;
  $('error').hidden = !s.lastError;
  $('error').textContent = s.lastError;
  $('interval').replaceChildren(...s.intervals.map((i) => {
    const o = document.createElement('option');
    o.value = i.id;
    o.textContent = i.label;
    o.selected = i.id === s.interval;
    return o;
  }));
  $('name').value = s.deviceName;
  const devices = Object.values(s.devices ?? {});
  $('devices-box').hidden = devices.length === 0;
  $('devices').replaceChildren(...devices.map((d) => {
    const li = document.createElement('li');
    li.textContent = `${d.name || 'Unnamed device'}${d.seen ? ` (changed ${when(d.seen)})` : ''}`;
    return li;
  }));
}

$('signin').addEventListener('click', async () => { await ask('signIn'); await refresh(); });
$('signout').addEventListener('click', () => { $('signout-choice').hidden = false; });
$('signout-keep').addEventListener('click', async () => { await ask('signOut', { wipe: false }); $('signout-choice').hidden = true; await refresh(); });
$('signout-wipe').addEventListener('click', async () => { await ask('signOut', { wipe: true }); $('signout-choice').hidden = true; await refresh(); });
$('sync').addEventListener('click', async () => {
  $('sync').disabled = true;
  try { await ask('sync'); } finally { $('sync').disabled = false; }
  await refresh();
});
$('interval').addEventListener('change', async (e) => { await ask('setInterval', { interval: e.target.value }); });
$('rename').addEventListener('click', async () => { await ask('setName', { name: $('name').value }); await refresh(); });

/** The library as a standard bookmarks file (the format every browser imports). */
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
$('download').addEventListener('click', async () => {
  const { items, collections } = await ask('library', { trash: false });
  const byCol = new Map([[null, []], ...collections.map((c) => [c.id, []])]);
  for (const i of items) if (i.url) (byCol.get(i.cat) ?? byCol.get(null)).push(i);
  const link = (i) => `    <DT><A HREF="${esc(i.url)}" ADD_DATE="${Math.floor(i.created / 1000)}"` +
    (i.tags.length ? ` TAGS="${esc(i.tags.join(','))}"` : '') + `>${esc(i.title)}</A>` +
    (i.notes ? `\n    <DD>${esc(i.notes)}` : '');
  const parts = ['<!DOCTYPE NETSCAPE-Bookmark-file-1>', '<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">',
    '<TITLE>PettiBox</TITLE>', '<H1>PettiBox</H1>', '<DL><p>'];
  for (const c of collections) {
    const list = byCol.get(c.id);
    if (!list.length) continue;
    parts.push(`  <DT><H3>${esc(c.name)}</H3>`, '  <DL><p>', ...list.map(link), '  </DL><p>');
  }
  parts.push(...byCol.get(null).map(link), '</DL><p>');
  const blob = new Blob([parts.join('\n')], { type: 'text/html' });
  const a = Object.assign(document.createElement('a'), {
    href: URL.createObjectURL(blob),
    download: `pettibox-${new Date().toISOString().slice(0, 10)}.html`,
  });
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
});

refresh();
