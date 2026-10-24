# PettiBox sync and multilingual OCR review

**Latest status:** see [the latest recheck](SYNC_OCR_LATEST_RECHECK.md). Findings below describe the original reviewed snapshot and are retained as historical evidence.

Reviewed October 17 2026. Audience: product owner and Android and extension developers.

PettiBox has substantial sync protections and useful multilingual OCR support, but several paths can make a saved item appear complete when its file or searchable text is still unavailable. Fix status accuracy and recovery first, protect edits during duplicate merging, and improve recognition of short and mixed-language text. The user should be able to save something once, see whether it is ready, and recover from a problem with one clear action.

This document explains the issues and proposed fixes. It does not implement application changes.

## Review scope and evidence

The review covers the working tree based on commit `f8ca7c6`, including the existing uncommitted OCR, search, database and sync changes. Source locations below refer to that snapshot. Kotlin paths are relative to `app/src/main/java/com/ghostgramlabs/pettibox/` unless stated otherwise.

Validation completed:

- Extension: `node --test test/*.test.js`, run in `extension`: **58 passed, 0 failed**.
- Android: `.\gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain`: **95 passed, 0 failures or errors, 0 skipped**, across 17 suites. The test task executed freshly; compilation dependencies were up to date.
- Read the OCR device tests, sync integration tests, worker implementations, merge paths, file transfer code and relevant settings/search UI.

The initial sandboxed Gradle attempt could not access the required wrapper; a retry with approved access to the normal build environment succeeded. The initial review used source inspection and unit tests. The subsequent emulator validation below adds Android instrumentation, actual recognition of generated images and a UI walkthrough. Live Google Drive integration and recognition of real-world camera scans remain untested. A passing unit suite does not establish OCR accuracy or exclude the scheduling races below.

**Evidence labels:** “Code finding” describes behavior directly visible in the implementation. “Concurrency risk” describes a specific possible interleaving that still needs a deterministic Android regression test. Priorities: P1 means address before relying on the affected behavior in a release; P2 means address next for reliability or usability.

## Priorities

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| S1 | P1 | Duplicate merging can overwrite an edit queued during sync | Concurrency risk |
| S2 | P1 | File sync can report completion with unresolved files | Code finding |
| S3 | P1 | Downloaded images and PDFs do not trigger local OCR | Code finding |
| O1 | P1 | OCR failures are silently reported as success | Code finding |
| O2 | P1 | Short and mixed-language readings are discarded | Confirmed in emulator |
| O3 | P1 | Re-reading can replace useful text with a worse result | Code finding |
| O4 | P2 | Model download failures remain labelled Downloading | Code finding |
| O5 | P2 | Increasing the PDF limit does not extend existing scans | Code finding |
| O6 | P2 | Duplicate OCR jobs can append duplicate text | Code finding |
| O7 | P2 | Re-read completion can lose a new request | Concurrency risk |
| O8 | P2 | PDF bitmap allocation lacks a height or pixel cap | Code finding |
| O9 | P2 | Mirrored EXIF orientations are handled incorrectly | Confirmed horizontal flip in emulator |
| U1 | P2 | Sync and OCR messages do not consistently describe actual state | Code finding |

## Sync findings and fixes

### S1 Protect pending edits during duplicate merging

**Location:** `data/sync/SyncEngine.kt:528`, `foldDuplicates`; compare its safeguards with `applyInbox` at line 442 and `settleMarks` at line 505.

The duplicate plan is built from `sync_meta` and then applied to the local library. It does not check the involved saves for pending outbox edits. In contrast, incoming merge application folds pending edits into the merged record, and mark settlement skips records with pending edits.

**Trigger and impact:** two devices save the same URL. After the round captures local changes, the person edits a title or note on a duplicate candidate. The fold still uses the older metadata and applies it over the local row. The outbox records the identity of the changed save, not the lost field values, so the later `commitLocal` cannot necessarily recover the edit. A queued edit can exist even before the fold plan is calculated.

**Fix:** inside the transaction that applies a fold, check every participating record for pending edits. Either fold those edits into metadata and recalculate the duplicate plan, or skip that entire group until the next round. Keep validation and application in one transaction. Preserve the existing user-visible conflict behavior.

**Acceptance test:** pause immediately before folding, update the survivor and then repeat with the duplicate, resume, and sync both devices. Each latest note/title must survive; the intended duplicate collapse must still converge. This race was identified by code inspection, not reproduced on a device in this review.

### S2 Only report file completion after checking unresolved work

**Location:** `data/sync/FileSyncWorker.kt:98`, `upload` around lines 161–168, and `download` around lines 199–204.

`download` silently leaves a placeholder when the file is absent from Drive and recovery folders. Upload also skips an unreadable or missing `file:` URI when hashing fails or the file no longer exists. The unreadable counter covers failed adoption of `content:` URIs, but not those file paths. A full worker run can then clear the error and report “All photos and files are in Google Drive”.

**Trigger and impact:** metadata arrives before the other device uploads its PDF, or a locally referenced file is missing. The library looks synced but the file cannot open. Metadata-first delivery is expected; reporting it as complete is the defect.

**Fix:** return explicit uploaded, downloaded, pending and failed counts. Re-query remaining placeholders before setting completion. Count failed hashes and missing local files. Show “Library synced · 2 files waiting for the other device” or “1 file needs attention”, with Retry or Re-add file as appropriate. Retain the existing Wi-Fi and battery explanations.

**Acceptance test:** sync metadata with its blob deliberately absent. The item must remain visibly pending, then become available automatically when the blob arrives. A deleted local file must produce a persistent actionable error rather than a completion message.

### S3 Schedule OCR when a synced file becomes available

**Location:** `data/sync/FileSyncWorker.kt:207–217`; `data/sync/SyncPorts.kt:91`, `afterApply`; `data/ocr/OcrRereadWorker.kt`, `read`.

The file-download transaction fills placeholders but does not enqueue OCR. Sync follow-ups handle reminders, link pictures and articles, not downloaded image/PDF OCR. Re-reading skips `pettibox-sync:` URIs and advances past those items.

**Trigger and impact:** device A saves an image without OCR, or without the language that device B uses. B receives it while its new-language re-read is running; the file is still pending. Once it downloads, no completion hook makes it searchable locally. A later remote OCR update or manual scan may rescue it, but neither is guaranteed.

**Fix:** after a successful download, schedule unique OCR work for affected image/PDF sources when auto-scan is enabled and the current language/model version has not processed them. Record a durable pending-index state in the same transaction as file availability so process death cannot lose the scheduling intent. Keep a startup reconciliation path.

**Acceptance test:** sync a Tamil image from a phone without Tamil OCR to a phone with Tamil ready. Delay the blob until after re-reading finishes. Download completion must eventually make a Tamil query find the save without opening Settings.

## Multilingual OCR findings and fixes

### O1 Preserve failure and cancellation states

**Location:** `data/ocr/OcrWorker.kt:34–50`; `data/ocr/PdfTextWorker.kt:37–51`; `data/ocr/TextReader.kt:90–108`.

Both workers catch recognition exceptions and return success. Missing/undecodable images can also become empty successful results. No text and failed recognition are indistinguishable. The initial Latin recognition call is outside the per-language error handler, so its failure prevents otherwise usable language engines from running. Broad `runCatching` also catches coroutine cancellation.

**Fix:** persist source-level states such as Queued, Reading, Ready, No text, Waiting for language, and Failed. Retry transient failures with bounded backoff; retain an actionable failure for unreadable files. Rethrow cancellation. Isolate each recognition engine so a failed Latin pass does not block others. Keep existing good text during failures.

**Acceptance test:** simulate decode failure, temporary model failure, cancellation and an actually blank image. Each must produce the correct distinct state. An installed alternative engine must still run when the Latin engine fails.

### O2 Keep short text and multiple scripts in one image

**Location:** `data/ocr/TextReader.kt:90–108`, `scriptScore` at line 163 and thresholds at lines 137–139. The unit test `TextReaderScriptTest.a_few_stray_letters_in_mostly_latin_text_dont` explicitly expects the short Russian word “Привет” to score zero.

The implementation selects one complete engine output. It requires at least eight script characters and a 20 percent script share before considering a non-Latin result. A valid short Chinese label or Russian greeting can therefore be rejected even when its recognizer reads it correctly. An image containing substantial Tamil and Hindi has no mechanism to combine correct regions from both engines. More recognized script characters are treated as better, which is not a general accuracy measure.

**Fix:** retain region-level text, bounding boxes and available confidence information. Select and combine non-overlapping readings in page order, removing duplicate Latin content. Use script evidence per region and allow short, plausible readings instead of imposing a page-wide eight-character rule. Offer a per-save language override when automatic selection is uncertain. As an interim fix, preserve validated additional readings for search without presenting duplicated raw output as a finished transcription.

**Acceptance test:** short Chinese and Russian labels, a mostly English receipt with one Tamil line, Tamil plus Hindi on the same page, and a negative-control image in an unsupported script. Verify expected words from every supported region and measure false positives. Do not simply remove the threshold without those controls.

### O3 Compare re-read quality before replacing text

**Location:** `data/ocr/OcrRereadWorker.kt:72–84`.

`better(new, old)` accepts any new reading containing enough of a currently enabled script. It does not compare coverage, confidence, page count or the languages preserved in the old text. Conversely, when the old text is nonempty, an improved Latin-only reading is not accepted by this rule.

**Trigger and impact:** enabling another language reprocesses an image. A shorter reading in an enabled script can replace a more complete previous transcription, making previously searchable words disappear. Re-reading after reducing the PDF limit can also replace more complete text with fewer pages if the new output passes the script test.

**Fix:** store results by source hash, page/region, engine/model version and language configuration. Preserve accepted regions and replace them only with a demonstrably better result or an explicit user choice. Do not silently reduce indexed page coverage. Keep user corrections separate from generated text. Rebuild the item search text from accepted source results.

**Acceptance test:** start with a bilingual scan containing known keywords, enable another language and return a shorter valid candidate. The previous keywords must remain searchable. Also verify that a genuinely improved English reading can be accepted.

### O4 Show actual language download state

**Location:** `data/ocr/OcrModelWorker.kt:35–48`; `ui/screens/settings/OcrLanguagesSheet.kt:119–123`.

Every selected language that is not ready is labelled “Downloading…”. This includes a job waiting for network and a download that has exhausted retries. At the retry limit the worker returns success even if a model remains unavailable. Polling readiness cannot explain the cause.

**Fix:** expose per-language Queued, Downloading, Waiting for internet, Ready and Failed states, with Retry for failure. Surface actionable storage or Play services problems. Make model readiness distinct from completion of re-reading older saves. Consider Wi-Fi preference and download size without requiring extra choices during ordinary saving.

**Acceptance test:** select a language offline, fail its download repeatedly, then reconnect. The UI must accurately explain each state and recover through Retry without toggling the language off and on.

### O5 Apply a higher PDF limit to existing saves

**Location:** `ui/screens/settings/SettingsViewModel.kt:524–548`; `data/local/SaveDao.kt:93,109`; `data/ocr/OcrPreferences.kt`, page-limit constants.

Changing the limit only updates a preference. “Scan existing saves” queues items with empty OCR, so PDFs that already have text from the first 10 or 30 pages are not extended. Even a generic re-read needs the O3 fix to accept an improved Latin-only result.

**Fix:** track pages scanned and total pages. On increasing the limit, offer “Read additional pages in saved PDFs” and queue only missing pages. Show “30 of 80 pages searchable” on the PDF. Lowering the future scan limit should not discard existing text. A later optimization is native text extraction for born-digital pages, with OCR as a fallback, preserving page order and the selected processing limit.

**Acceptance test:** scan a PDF at 10 pages, raise the limit to 30, and search for a unique word on page 20. It must appear after processing; the user must see progress and remaining coverage.

### O6 Make OCR work repeatable without duplicating text

**Location:** `data/ocr/OcrWorker.kt:38–41,63,77`; `data/ocr/PdfTextWorker.kt:42–45,70`; `data/local/SaveDao.kt:450–464`.

Ordinary OCR requests are not unique work. Attachment recognition writes its own text and then appends it to the parent. Repeating a request appends the same text again; process interruption between the two database writes can also leave attachment and parent text inconsistent. Atomic append prevents one worker overwriting another, but does not make retries idempotent. Re-read replacement can race with these ordinary append jobs.

**Fix:** use a unique work identity derived from source identity and processing version. In a database transaction, update the source result and deterministically rebuild the parent's indexed text from its sources in stable order. Use result versions to prevent an older job overwriting a newer one. Avoid treating OCR completion as a user edit in sorting, consistent with `replaceOcrText`.

**Acceptance test:** queue the same attachment twice, repeat after a process interruption, and overlap it with re-reading. The parent must contain each source exactly once, and newer accepted results must survive.

### O7 Atomically finish a re-read generation

**Location:** `data/ocr/OcrRereadWorker.kt:44–55`; `data/preferences/OcrPreferences.kt`, `requestReread`, `rereadRequest` and `setRereadCursor`.

Completion reads the request generation and later clears the cursor in a separate preference edit. A new request between those operations can increment the generation while leaving the existing cursor in place, only for the old worker to clear it. The subsequently queued worker then sees no cursor and exits.

**Fix:** provide one atomic DataStore operation that compares the expected request generation and clears the cursor only if it still matches. Otherwise reset/retain work for the newer generation. Prefer explicit requested and completed generations so startup reconciliation can detect unfinished work.

**Acceptance test:** inject a request between the completion check and cursor update. The newer generation must still process the library after the first worker exits. This is a concurrency risk requiring a deterministic regression test.

### O8 Bound PDF memory and checkpoint long jobs

**Location:** `data/ocr/TextReader.kt:65–84`; `data/ocr/OcrRereadWorker.kt:41–63`.

PDF rendering fixes width at 1400 but scales height proportionally without a pixel budget. A very tall page can allocate a large bitmap. For example, a page with a 10:1 height-to-width ratio needs about 78.4 MB for its 1400 × 14000 ARGB pixels alone. Multiple workers and recognizers add memory pressure. The re-read time limit is checked between batches, not inside a long PDF or each engine run.

**Fix:** cap total pixels and both dimensions, tile oversized pages, and limit concurrent decoding/recognition. Recycle bitmaps even when rendering throws. Check cancellation between pages/engines and checkpoint page-level progress so one long PDF does not repeatedly restart from page one.

**Acceptance test:** process a tall scanned receipt PDF and several concurrent PDFs on a low-memory emulator. Verify bounded peak memory, resumable progress and no skipped remainder. This review did not measure memory on a device.

### O9 Correct mirrored image orientation

**Location:** `data/ocr/OcrImageLoader.kt`, EXIF orientation mapping and rotation matrix.

Horizontal flip is treated as normal, vertical flip as a 180 degree rotation, and transpose/transverse as rotation alone. These orientations require reflection as well. Mirrored text can reach the OCR engine and become unreadable.

**Fix:** implement all eight EXIF transforms using the appropriate rotation and reflection matrix. Also correct the stated 2048-pixel long-edge cap: the current sampling loop can leave an image close to 4096 pixels wide/high. Enforce a final pixel budget or document the actual bound.

**Acceptance test:** encode one readable reference image in all eight EXIF orientations. Every transformed image should have the same upright content and searchable text, within a defined memory budget.

## Make the app easier to use

### U1 Use state-based messages and accurate help

`SyncEngine.round` records a successful sync timestamp even for `NeedsReview` (`data/sync/SyncEngine.kt:77`). `SyncSettingsCard` can consequently show a recent “Last synced” heading while its lower section says sync is paused. The review branch actually still uploads local changes, so “Sync is paused” is also broader than the behavior. Give pending review, reconnect and incomplete transfers precedence over a healthy heading; keep last attempted and last fully applied timestamps separate.

`OcrLanguagesSheet.kt:89` says “Your images never leave your phone”, but enabling file sync uploads them to the user's Drive. Replace this with: **“Text recognition runs on your device. If you enable sync or Drive backup, files are also copied to your Google Drive.”**

`SettingsScreen.kt:1315` still describes English scanning and a fixed 30-page limit. Update help to describe enabled languages and the selected page limit. Drive settings already have useful Wi-Fi, battery and account explanations; preserve those instead of adding another setup wizard.

### Recommended everyday flow

1. **Save:** immediately confirm “Saved on this device”. Do not delay saving for language downloads, recognition or Drive.
2. **Read text automatically:** use device-language defaults, show “Making text searchable” when appropriate, and keep progress unobtrusive. Show language management only when needed or deliberately opened.
3. **Search:** display available results while indexing continues. When relevant work remains, show “Some saved images are still being read”. Distinguish no matches from pending processing.
4. **Open a save:** show file availability and text status separately. Provide one relevant recovery action: Retry reading, Choose language, Retry download, or Re-add file. Offer editable extracted text while keeping corrections safe from automatic replacement.
5. **Sync:** use one visible summary such as “Up to date”, “Saved here · waiting for internet”, “Library synced · 2 files pending”, or “Review 12 changes”. Keep Sync now as the recovery action and put advanced intervals/device removal below the primary status.

For bulk removal review, provide a preview of affected saves before Apply here too or Put them back. Keep the existing explanation that putting them back affects every device. Users should not need to understand metadata, shards, models or outboxes to resolve a problem.

## What is already working and should be preserved

The current implementation includes account checks, reset epochs, format validation, outbox capture, transactional incoming merges, a mass-change review guard, resumable file transfers, content-hash validation and retention-aware cleanup. The extension suite passed cases for edit survival, account isolation, reset handling, duplicate folders, wrong-shard rejection, unsafe numeric values, deleted-file recovery and cross-client vectors.

Earlier results in `audit/sync-design-recheck-results.json` refer to revision `17b3676`. They should not be treated as current failures. In particular, current regression tests cover the retired-folder and codec issues; Android `DriveSyncClient.resetEverything` now updates all listed duplicate reset markers. Retain and extend those tests rather than reopening the old findings solely from the archived report. Android reset behavior still deserves live integration coverage.

Search already uses Unicode FTS tokenization and a substring fallback for several scripts written without spaces. Preserve these improvements. Recognition and search normalization should be evaluated together; successful OCR alone does not prove that a user's keyboard query will match.

## Implementation order and release checks

**First:** fix S1, S2 and O1. Protect edits and stop reporting incomplete work as successful. Add deterministic failure/race tests alongside those changes.

**Next:** introduce durable source-level OCR results and states, then implement S3, O3, O5, O6 and O7 on that foundation. A practical record includes source hash, model/language configuration version, state, pages completed, error/retry metadata and accepted text. Keep local processing state local; if accepted text or new metadata is shared, define backward-compatible sync behavior explicitly.

**Then:** improve regional multilingual selection, model download UI, bitmap bounds, EXIF transforms and help text. Keep existing data and search indexes usable throughout any database migration.

Before release, add a device test matrix covering:

| Area | Required scenarios | Pass condition |
| --- | --- | --- |
| Edit safety | Edit survivor/duplicate during fold; restart during sync | Latest edits survive and devices converge |
| File sync | Metadata before blob; missing local file; offline/Wi-Fi transition | Accurate pending/error state and automatic recovery |
| Languages | Latin, Malayalam, Tamil, Hindi, Arabic, Chinese, Japanese, Korean; short and mixed-script samples | Expected words survive recognition and can be found by typed queries |
| OCR failures | Missing model, unreadable URI, blank image, cancellation | Distinct states, bounded retries, no false success |
| Re-reading | Enable language during completion; overlap workers; lower then raise page limit | No lost request, duplicate text or reduced accepted coverage |
| PDF and images | Tall pages, long PDF, rotated/mirrored images, low-memory device | Correct orientation, bounded resources and resumable work |
| Accessibility | Large font, TalkBack, RTL text and language names | Status/actions remain readable and operable |
| Upgrade | Existing library through database migration and mixed app versions | Existing searches, corrections and sync remain intact |

Use realistic receipts, screenshots and scans as well as synthetic text images. Track recognition errors, successful keyword retrieval, time to searchable text and peak memory per language/device. The present unit tests and synthetic device fixtures are valuable foundations, but they do not yet demonstrate the complete everyday experience described here.

## Emulator validation on October 17 2026

### Environment and boundaries

Tested the current debug application on `emulator-5554`, `Medium_Phone_API_35`, Android 15 / API 35, x86_64, with a 1080 × 2400 display. The app and test APK were built/installed through Gradle. Production application code was not changed. An opt-in diagnostic instrumentation class was added to make the new OCR checks reproducible; it writes test images/results into app cache and restores the selected language set in a finally block.

The sync integration tests run the actual Android sync engine, Room databases, SQL triggers and merge logic against an in-memory Drive transport. They simulate two independent libraries inside one emulator. This verifies more than the JVM merge tests, but is not a test of two physical devices or Google's live Drive service. In the visible app, sync was off and Google Drive was not connected. No account was connected and no personal cloud data was changed.

### Existing instrumentation results

| Suite | Cases | Result |
| --- | --- | --- |
| TwoDeviceSyncTest | 43 | 42 passed initially; 1 assumption skip, then passed on targeted retry |
| SyncTablesTest | 9 | All passed |
| OcrLanguagesDeviceTest | 14 | All passed |

The initial XML report records **66 tests, 0 failures, 0 errors, 1 skipped**. The skipped case, `a_save_that_arrives_before_its_collection_is_filed_when_the_collection_comes`, assumes the randomly generated item and collection IDs fall into different shards. They did not in the first run. A targeted retry passed, giving successful execution of all 66 distinct existing cases across the two runs. This is a test-fixture nondeterminism issue: use a deterministic item ID in a different shard so this coverage cannot disappear by chance.

The OCR suite successfully checked generated sentence images in Malayalam, Tamil, Telugu, Kannada, Bengali, Russian, Arabic, Hindi, Chinese, Japanese, Korean and Thai, plus two multi-model/negative-control scenarios. These successes establish a useful baseline, not full accuracy across fonts, short labels, mixed pages or camera images.

### Additional diagnostics that reproduced defects

The opt-in `OcrAuditProbeTest` records outcomes rather than asserting that the app is correct. Its successful execution means evidence was collected; it does **not** mean every diagnostic case passed. Four of the five cases failed their intended behavior:

| Case | Observed result | Interpretation |
| --- | --- | --- |
| Short Chinese label `天气` | Raw Chinese ML Kit output was `天气`; final app output was empty | Confirms O2 discards correct short recognition |
| Short Russian greeting `Привет` | Final app output was `lpuBeT` | Confirms the short-word experience fails; the underlying Tesseract candidate was not captured separately |
| Hindi and Chinese on one image | Both ML Kit engines recognized their respective lines; app kept Hindi plus noise and dropped the Chinese lines | Confirms O2 selects one reading and loses another supported script |
| English receipt control | Both expected words, `Grocery` and `Thank`, survived | Control passed |
| JPEG with horizontal-flip EXIF | Red-left/blue-right pixels stayed unreflected instead of becoming blue-left/red-right | Confirms O9 for horizontal reflection; all eight orientations were not exercised |

For the Chinese case, the raw engine and final app result came from the same generated source image. The mixed-page case independently captured the Hindi and Chinese recognizer outputs before calling the app reader, separating a model-recognition limitation from the application's selection behavior.

**Fix priority after this run:** O2 and O9 are now reproduced defects. Keep S1 and O7 labelled concurrency risks: the existing integration tests do not inject the exact interleavings identified in this report. S2/S3 and the other OCR worker issues remain code findings until targeted fault-injection tests verify them.

### Visible usability observations

I opened Home, Settings, Text recognition, Languages in images and Sync between devices. Language names rendered correctly in the inspected screenshot, and enabled Chinese, Hindi and Russian showed Ready. The existing configured language set was restored after diagnostics. The language sheet visibly contains the inaccurate “Your images never leave your phone” claim described in U1.

The Settings page places appearance and a large collection-management section before text recognition. Sync is farther down, after reminders, Help and Privacy; reaching it from the top required four substantial upward scrolls in this walkthrough. This makes two important features difficult to discover. Put a compact Sync status card and Text recognition entry near the top, and move collection administration and extended help into their own destinations. This is an observed navigation cost and a design recommendation, not a measured user-study result.

The sync-off screen and disconnected Drive state were inspected, but authenticated sync progress, conflict review UI, TalkBack, large-font behavior, offline downloads and end-to-end multilingual search were not exercised in this session.

### Saved evidence and reproduction

Evidence is in [emulator-evidence](emulator-evidence/):

- [Recognition and orientation results](emulator-evidence/ocr-probe-results.json) contains exact expected words, engine output and final app output.
- [Initial instrumentation results](<emulator-evidence/TEST-Medium_Phone_API_35(AVD) - 15-_app-.xml>) contains per-case results for the 66-test run.
- [Late collection retry](emulator-evidence/late-collection-retry.txt) records the successful rerun of the skipped case.
- [Language settings screenshot](emulator-evidence/languages.png), [sync settings screenshot](emulator-evidence/sync-settings.png), and [Home screenshot](emulator-evidence/home.png) preserve the inspected UI; matching XML dumps are in the same folder.

Run the existing suites:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.ghostgramlabs.pettibox.data.sync.TwoDeviceSyncTest,com.ghostgramlabs.pettibox.data.sync.SyncTablesTest,com.ghostgramlabs.pettibox.data.ocr.OcrLanguagesDeviceTest'
```

Run the additional opt-in diagnostic:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.ghostgramlabs.pettibox.data.ocr.OcrAuditProbeTest' '-Pandroid.testInstrumentationRunnerArguments.auditOcr=true'
adb -s emulator-5554 shell run-as com.ghostgramlabs.pettibox cat cache/ocr-audit/results.json
```

The diagnostic is skipped unless `auditOcr=true` is supplied. Language model downloads may require internet; the Gradle `--offline` flag controls Gradle dependency resolution, not the emulator's model downloads. After fixing these behaviors, convert the diagnostic expectations into normal acceptance assertions so regressions fail the release test suite.

## Status after fixes (October 17 2026)

All findings were addressed in the working tree. Verified on `Medium_Phone_API_35` (Android 15): **98 unit tests and 74 instrumentation tests pass, 0 failures** (2 opt-in tools skipped). The probe's expectations are now ordinary assertions in `OcrLanguagesDeviceTest`; `OcrAuditProbeTest` was removed.

| ID | Status | What changed | Proven by |
| --- | --- | --- | --- |
| S1 | Fixed | `foldDuplicates` skips any group with a pending outbox edit, checked inside the fold transaction; it folds on the next round after the edit is recorded. | `TwoDeviceSyncTest.an_edit_made_while_duplicates_are_folded_survives` (edits injected at the fold) |
| S2 | Fixed | Upload counts missing local files; completion re-queries placeholders. Status reads "Library synced · N files waiting for another device", or names missing files, instead of "All … in Google Drive". | Code; not fault-injected against live Drive |
| S3 | Fixed | A downloaded image/PDF schedules OCR (unique per file). Its result replaces the other phone's text only if better. | Code. Gap: process death between download and scheduling skips that file's local reading |
| O1 | Mostly fixed | Each engine runs in isolation (a Latin failure doesn't stop others); cancellation is rethrown; unreadable files are distinct from engine failures, which retry up to 3 times; existing text is kept on failure. | Unit and device tests. Not done: a persisted per-file state (Queued/No text/Failed) shown on the save |
| O2 | Fixed | Selection is per line (box, script share, engine confidence), not per page: short labels and two scripts on one page are kept, overlaps resolved by confidence × length. | `a_short_chinese_label`, `a_short_russian_word`, `hindi_and_chinese_on_one_page_keep_both`, `an_english_receipt_with_one_tamil_line_keeps_both`, unknown-script negative control. Not done: per-save language override |
| O3 | Fixed | `TextReader.improves`: text in a script that can't be read now is kept; a reading adding a newly readable script wins; otherwise it must keep ≥ 90% of the letters. Used by re-read, normal OCR and downloads. | `TextReaderScriptTest` |
| O4 | Fixed | Per language: Ready / Downloading / Waiting for internet / Couldn't download + Try again (after the worker's last retry). | Emulator: offline showed "Waiting for internet", then "Ready" on reconnect without toggling |
| O5 | Mostly fixed | Raising the page limit queues a PDFs-only re-read whose longer result is accepted; lowering keeps existing text. Not done: "30 of 80 pages" coverage display, native text extraction |
| O6 | Fixed | One unique WorkManager job per file; a file's text is stored and the save's text rebuilt from its files in one transaction (no appends); OCR no longer bumps "Recently edited". Fixed a related bug: "Scan existing saves" scanned added PDFs as the save's own file, overwriting the other files' text. | Code |
| O7 | Fixed | `OcrPreferences.finishReread` compares the request generation and clears or restarts in one DataStore edit. | Code; reproduced the quick double-toggle case in the app |
| O8 | Mostly fixed | PDF pages are drawn in 1400 × ≤2000 slices (≈11 MB each, overlapping, deduplicated); at most two pictures are read at once; cancellation is checked per page. Not done: page-level checkpoint inside one long PDF |
| O9 | Fixed | All eight EXIF transforms with reflection; final scale enforces the 2048-pixel long edge. | `every_exif_orientation_is_turned_upright` (stored pixels built from the EXIF definitions) |
| U1 | Mostly fixed | Sync heading shows "Reconnect…" / "Waiting for your choice" before "Last synced"; review copy says other devices' changes wait while yours still go out; the privacy line and the help text now say what happens; Search says when images are still being read; Sync and Text recognition moved to the top of Settings. Not done: separate last-attempted and last-applied timestamps, a preview of saves in the bulk-removal review, moving collection management to its own screen |

Test fixture: `a_save_that_arrives_before_its_collection…` now picks a collection id in a different shard deterministically, so it can no longer be skipped by chance.

Still untested: live Google Drive, real camera photos and scans, TalkBack and large fonts, low-memory devices.
