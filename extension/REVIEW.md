# Extension and sync review — 2026-10-08

Reviewed the Chrome worker, Drive transport, popup/options/viewer, sync engine, and Android sync engine/scheduler. Changes are limited to the extension and tests.

## Fixed

- Serialize settings, sign-out, and file downloads with sync/edits, so a running operation cannot overwrite settings or repopulate a wiped library/cache.
- Persist explicit sign-out; block sync even if Chrome still supplies a token, and clear alarms and cached Drive sessions.
- Persist edited records, pending upload shards, and logical clock in one storage operation to close the interruption window.
- Share initial metadata creation across concurrent first-use requests to avoid competing device IDs.
- Check the retained library's account before reading/uploading sync records; stop on account mismatch.
- Keep the local library usable without Google sign-in and show reconnect controls when needed.
- Recreate the favicon when refreshing a card after a saved image replaced it; ignore stale image render responses.

## Validation

31 Node tests pass: 24 existing and 7 added. Includes 250 Kotlin merge vectors, 200 diff vectors, and 120 duplicate-fold vectors. New regressions cover interrupted persistence, account switching, sign-out, settings during sync, first-use identity, authentication failure, and repeated picture-card rendering. JavaScript syntax and git diff --check pass.

Chrome APIs and Drive were mocked. No live Chrome/Google/Android session was exercised. Android sources were reviewed but Android build/device tests were not run. These results do not guarantee issue-free production operation.

## Recommended improvements

1. Add request timeouts and bounded retries with backoff. The worker currently ignores the engine's retry flag, leaving failed uploads waiting for a periodic round or another trigger. Move large downloads outside the edit queue using cancellation/cache-generation checks to preserve wipe safety.
2. Protect open editors from concurrent sync: currently saving submits every field, potentially overwriting newer remote values received while the editor was open. Send only fields changed from the original snapshot and flag conflicts on those fields.
3. Clarify “Manual only”: it disables periodic sync, but edits, startup, and popup opening can still trigger sync. Android has similar scheduling behavior. Rename the setting or enforce strictly explicit sync consistently.
4. Add pagination/Show more: the popup silently limits results to 200.
5. Display actionable errors for all popup/options actions; several handlers currently leave rejected promises without visible feedback.
6. Define browser recovery parity with Android: Android has mass-removal review/safety backup; browser gone records disappear even from Recently deleted.
7. Before release, exercise live save/edit/delete/restore in both directions, simultaneous edits, offline/reconnect, worker restart, expired auth, account switching, reset marker, sign-out with/without wipe, and phone photos/PDFs.

Chrome worker lifecycle reference: https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle

## Follow-up — 2026-10-08

The fixes above were reviewed and kept. One was reworked: queueing file
downloads behind sync and edits made list pictures wait for whole rounds and
let one large PDF hold up every save. Downloads run outside the queue again;
a generation counter (bumped by sign-out) stops a download that finishes after
a wipe from putting its file back.

Recommendations addressed:

1. **Timeouts and retries.** Every Drive request times out (30 s; 2 min for
   file transfers). A round that fails for a passing reason retries by itself
   after 1, 2, 4… minutes, at most 30; success resets it.
2. **Editor.** Saving sends only fields changed since the editor opened, so a
   value synced in meanwhile isn't overwritten. Tested.
3. **"Manual only"** is now **"Only while in use"** in the extension and the
   app: no background syncing, but opening the popup or app and editing still
   sync. That is the intended behaviour; the label now says so.
4. **Show more** replaces the silent 200-result cut-off.
5. **Errors.** Every popup and options action reports failures on the page.
6. **Recovery parity.** The browser now pauses on many removals at once
   ("Keep mine" / "Apply", as on the phones; Keep mine restores them for every
   device), and a save removed for good elsewhere stays in the browser's
   Recently deleted for 30 days and can be restored. Both tested.

Still open: **7** (live end-to-end checks on real devices and accounts), which
needs a person with the phones and Google account.

Validation: 36 Node tests pass (24 + the reviewer's 9 + 3 new: mass-removal
review, restoring a save removed elsewhere, editor sends only changes); the
extension loads and runs without errors in headless Chrome; Android compiles
and its unit tests pass.
