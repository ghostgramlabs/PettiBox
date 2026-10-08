# PettiBox for Chrome

Save the page you're on to PettiBox, and search, edit or delete your saves from the browser.
The extension syncs with the PettiBox Android app through the user's own Google Drive, using
the same "Sync between devices" format as the app (see `../sync-spec/README.md`). The browser is
just another device: it keeps its own merged copy of the library and publishes its own files.

Manifest V3, plain ES modules, no build step.

| File | What it does |
|---|---|
| `sync/model.js` | Merge rules, a port of `SyncModel.kt` |
| `sync/codec.js` | Record and device-file JSON, a port of `SyncCodec.kt` |
| `sync/engine.js` | The browser's library and sync round (storage and Drive passed in) |
| `sync/bytes.js` | gzip and MD5 for device files |
| `drive.js` | Google Drive calls through `chrome.identity` |
| `background.js` | Service worker: schedule, right-click "Save to PettiBox", popup requests |
| `popup.*` | Save this tab, search, edit, pick between versions edited on two devices |
| `options.*` | Account, interval, this browser's name |

## Tests

```
cd extension
node --test test/*.test.js
```

They check the JavaScript engine against every golden case the Kotlin engine generated
(`../sync-spec/vectors/cases.json`), that device files cross both ways between the app and the
extension, and two browsers syncing through a fake Drive. CI runs them on every push.

## Setup (one time, in Google Cloud Console)

The extension signs in with `chrome.identity.getAuthToken`, which needs an OAuth client of type
**Chrome Extension** **in the same Google Cloud project as the Android app**. `drive.file` access
belongs to the project, so that's what lets the extension see the files the app made (tested
2026-09-03). A client in another project would see an empty Drive.

1. Google Cloud Console › the PettiBox project › APIs & Services › Credentials › Create
   credentials › OAuth client ID › Application type **Chrome Extension**.
2. Item ID: `ejdpmogmafbecaokmpgpfoipoafinpoi` (the development ID, fixed by `"key"` in
   `manifest.json`).
3. Copy the client ID into `manifest.json` › `oauth2.client_id` (done for the development ID: `785489867134-pedr…`).
4. The scope is `drive.file`, already on the consent screen, so nothing there changes and no
   new verification is needed.

Check the project: the digits before the first `-` in the new client ID must match the Android
client IDs' digits.

## Try it

1. `chrome://extensions` › turn on Developer mode › **Load unpacked** › pick this folder.
2. Click the PettiBox icon › **Sign in with Google**, using the account the phone uses.
3. On the phone: Settings › Sync between devices › Turn on sync.
4. Save a tab in Chrome; tap Sync now on the phone. Edit it on the phone; click ⟳ in the popup.

## Publishing

The Chrome Web Store gives a new item its own ID. After the first upload, copy the store's public
key (Developer Dashboard › Package › Public key) into `manifest.json` › `key`, and add a second
Chrome Extension OAuth client for the store ID in the same project. Ship as **Unlisted** until
the app release with sync is rolled out.

The development key pair lives outside the repo, in `%USERPROFILE%\.pettibox-keys\`
(`dev-key.pem` is the private key; never put it in this folder, or Chrome would load it with the
extension). It's only needed to pack a `.crx` by hand; the ID comes from the public key in
`manifest.json` › `key`, so the extension keeps its ID even without it.

## What the browser doesn't do (yet)

- Photos, PDFs and files: the browser shows saves that have them, but only phones download them.
- Locked collections never show here; the browser has no way to unlock them.
- Reminders stay on phones.
- Edge and Firefox: Edge lacks `chrome.identity.getAuthToken`, so it needs another sign-in path.
