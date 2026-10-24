# Changelog

User-facing changes to PettiBox, newest first. Version numbers match the Google Play release
(`versionName` in `app/build.gradle.kts`).

## Unreleased

- Unused photos and files are only removed from Google Drive once every device listed in
  Settings shows it no longer uses them: first moved to a PettiBox "files-removed" folder for
  30 days (moved straight back if anything needs them), then deleted. A phone that was offline
  for months can't come back to a missing photo.
- Saves deleted for good no longer leave their title, note and link in Google Drive once every
  device has the deletion and 30 days have passed. If a device that missed the deletion edits
  the save, it comes back whole.
- Sync hardening from the design audit (audit/SYNC_SYSTEM_DESIGN_AUDIT.md): a library only syncs
  with its own Google account; "Delete synced data" can't be undone by a device that was mid-sync;
  unused photos go to Drive's trash (recoverable) instead of being deleted, and come back if a save
  needs them; duplicate Drive folders are merged without deleting anything; damaged or malformed
  sync files are rejected whole; a device re-sends its own files if they go missing; a pending
  "removal review" no longer holds back your own changes, and is asked again if more removals
  arrive; a newer app version on another device is reported; and no safety copy means no mass
  removal.
- Sync fixes from an architecture review: a save that reaches a device before its collection is
  filed into it when the collection arrives (and is never taken out of it); an edit made while a
  sync is applying changes is no longer overwritten; and a phone set up by copying another one
  (phone-to-phone transfer) gets its own sync identity instead of overwriting the other's files.
- Sync is quicker to show changes: an edit goes out about 3 seconds after you stop (it used to
  wait a minute on the phone and half a minute in Chrome), the app checks for changes every
  30 seconds while it's open, and tapping Sync during a sync runs one more after it.
- Home has a Sync button and a "Synced 2 min ago" line while sync is on.
- Saves list in the same order on every device: "Recently edited" uses the time of the last
  real edit, and saves with the same time are always ordered the same way.
- Tags read the same way in the app and the Chrome extension (a leading # is dropped and
  tags are up to 24 characters), so a tag no longer changes shape on its way between them.
- Big photos, videos and PDFs (5 MB and up) now sync in pieces: after a dropped connection,
  uploads and downloads carry on where they stopped instead of starting over.
- Removing one photo or file from a save now sticks. Before, it was only removed once the
  "Item removed" message was swiped away, and leaving the screen undid it.
- Photos, PDFs and files that no save on any device has used for 30 days are now removed
  from Google Drive, so deleted files stop using your Drive storage.
- Removing one file from a save with several no longer brings it back on other devices
  (it reappeared in the Chrome extension when it was the first file).
- Chrome extension: a save with several photos, PDFs or files now opens as a gallery
  (arrows, ← / → keys and a strip of thumbnails) instead of showing only the first file,
  and its card shows how many files it has.
- A save edited on two devices at once now pulses amber in the app and the Chrome extension
  (with a count on the extension's toolbar icon) until someone picks a version. Choosing on
  any one device is enough: the others stop asking after their next sync.
- PettiBox is now open source under the Apache License 2.0.
- Locked collections: lock a collection from Edit collection and it disappears from Home,
  Browse, search, the widget and nudges until you open Browse › Locked with your fingerprint,
  face or phone PIN. Reminders from it don't show the title. To leave no trace in Browse,
  turn off "Show Locked in Browse" and open them from Settings › Privacy instead.
- Links without a preview image get a screenshot of the page instead, and any link can switch
  to one with "Use a page screenshot as the picture".
- With more than 8 collections, moving a save opens a searchable collection list.
- Sync between devices through your own Google Drive: every phone and tablet keeps the same
  library, on a schedule you choose (30 minutes to weekly, or manual), plus when you open the
  app and shortly after a change. Photos and files sync too (Wi-Fi only by default). Edits made
  on two devices at once are both kept until you pick one; a delete never wipes an edit made
  elsewhere; anything removed by sync waits in Recently deleted; and a large removal from
  another device pauses for your OK, with a backup made first.
- PettiBox for Chrome: save the page you're on in one tap (or Ctrl+Shift+S), see pictures, search,
  edit, and download your library as a bookmarks file. It syncs with the app through your Drive.
- Everything PettiBox keeps in Google Drive is now in one "PettiBox" folder (Backups and Sync
  inside). The old "PettiBox Backups" folder moves in by itself, with every backup.
- Turning sync off never deletes anything on the phone; you can also remove this phone's copy from
  Drive, or delete all synced data from Drive (sync then stops on every device, and each keeps its
  library).

## 2.0.12 - 2026-10-02

Versions 2.0.9 to 2.0.11 were never rolled out; their changes ship in 2.0.12.

- Share several PDFs or files at once, or pick several in the app.
- App lock locks as soon as the screen turns off, and you can choose when it relocks.
- The widget shows your saves while PettiBox is unlocked.
- Restore straight from backups saved on this phone.
- Search finds every match and updates as you save.
- Fixed: Next/Previous in the photo viewer, images saved as PDFs, duplicate links that differ
  only by capital letters, drafts lost when rotating the screen, and empty notes being saved.
- Fixed: files left behind by failed or duplicate restores.

## 2.0.8 - 2026-10-01

- Pinch-zoom for saved images and an in-app PDF reader.
- Keep link articles for offline reading (text only).
- Unread filter, a weekly nudge, Recently deleted, a home-screen widget, app shortcuts and
  App lock.
- New flat app icon.
- Targets Android 16 (API 36).

## 2.0.7 - 2026-07-20

- Switch the connected Google Drive account.

## 2.0.6 - 2026-07-19

- Restoring a backup skips saves you already have, so using PettiBox on two devices no longer
  creates duplicates.
- Rate PettiBox or email support from Settings.
- Text recognition on large photos uses far less memory.

## 2.0.4 - 2026-07-07

- Import bookmarks from Chrome, Firefox, Raindrop, Pocket and other apps (HTML/CSV); folders
  and tags carry over and duplicates are skipped.
- Automatic backup to your own Google Drive, with one-tap restore on a new phone.
- Export your links as a CSV file.
- Starter collections can be renamed, restyled or removed.
- Clearer backup settings and a confirmation before restoring onto a shelf that isn't empty.

## 2.0.3 - 2026-07-06

- Easier deleting of saves and better clipboard handling.

## Earlier versions

Releases 1.0 to 2.0.2 (May 2026) are recorded in the git history.
