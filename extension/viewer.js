// Shows a photo, PDF or file a phone shared, fetched from Drive by the
// background worker (checked against its content hash) and cached.
const params = new URLSearchParams(location.search);
const sha = params.get('sha') ?? '';
const kind = params.get('kind') ?? 'FILE';
const box = document.getElementById('box');
document.title = params.get('title') || 'PettiBox';

const REASONS = {
  missing: 'This file is still on your phone. Photos and files upload on Wi-Fi unless you change that in the app (Settings › Sync between devices).',
  signin: 'Sign in to PettiBox (click its toolbar icon) to see this.',
  damaged: 'This file arrived damaged. Try again after the phone syncs.',
};

function say(text) {
  box.replaceChildren(Object.assign(document.createElement('p'), { textContent: text }));
}

try {
  const res = await chrome.runtime.sendMessage({ type: 'file', sha });
  if (res?.error) throw new Error(res.error);
  if (!res?.result?.ok) {
    say(REASONS[res?.result?.reason] ?? 'Couldn\'t open this file.');
  } else {
    const hit = await (await caches.open('pettibox-files')).match(`https://files.pettibox.invalid/${sha}`);
    const blob = await hit.blob();
    const url = URL.createObjectURL(blob);
    if (kind === 'IMAGE' || blob.type.startsWith('image/')) {
      box.replaceChildren(Object.assign(document.createElement('img'), { src: url, alt: document.title }));
    } else {
      // PDFs and other files: hand them to Chrome's own viewer.
      location.replace(url);
    }
  }
} catch (e) {
  say('Couldn\'t reach Google Drive. Check your connection and try again.');
}
