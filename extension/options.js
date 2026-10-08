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
$('signout').addEventListener('click', async () => { await ask('signOut'); await refresh(); });
$('sync').addEventListener('click', async () => {
  $('sync').disabled = true;
  try { await ask('sync'); } finally { $('sync').disabled = false; }
  await refresh();
});
$('interval').addEventListener('change', async (e) => { await ask('setInterval', { interval: e.target.value }); });
$('rename').addEventListener('click', async () => { await ask('setName', { name: $('name').value }); await refresh(); });

refresh();
