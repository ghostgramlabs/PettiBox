# PettiBox sync and OCR fix verification

October 17 2026

**Not all issues are fixed.** The new implementation fixes the reproduced short-text, mixed-script and EXIF problems, protects edits during duplicate folding, and improves Settings. However, OCR text retention still fails a new regression test. Failure reporting, re-read scheduling and recovery also retain gaps.

This recheck supersedes the status conclusions in [the original review](SYNC_OCR_USABILITY_REVIEW.md). It checks the current uncommitted working tree based on `f8ca7c6`; the original report remains historical evidence. Production code was not changed during verification. One regression test was added and intentionally remains failing until the text-retention defect is fixed.

## Validation performed

| Check | Fresh result |
| --- | --- |
| Extension test suite | 58 passed |
| Existing Android unit suite | 98 passed before adding the new regression |
| Android emulator instrumentation | 74 discovered, 72 passed, 0 failed, 2 skipped |
| New OCR retention regression | 1 executed, 1 failed |
| Settings walkthrough | Sync and text recognition now visible near the top |

Instrumentation ran on `emulator-5554`, Android 15 / API 35, x86_64. It included 44 two-library sync tests, 9 sync-table tests and 19 OCR tests. The two skipped cases are opt-in clipboard/sample-image helpers, not skipped product assertions. The previously nondeterministic late-collection test now chooses a different shard deterministically and passed.

The 19 OCR cases include the newly added short Chinese label, short Russian word, Hindi plus Chinese page, English receipt plus Tamil line, and all eight EXIF orientations. All passed. The new duplicate-fold edit-survival test also passed.

Sync tests use real Android engine/Room code with an in-memory transport. Live Google Drive transfer, process-death fault injection, long-PDF memory profiling and physical-device camera-image accuracy were not tested. No connected Google account was available for live Drive verification.

## Status of every original finding

“Verified” means a relevant fresh test passed. “Addressed in code” means the principal code path has changed appropriately, but its service/failure behavior has not been directly tested. “Partial” means a specific remaining path prevents closing the finding.

| ID | Status | Result |
| --- | --- | --- |
| S1 Duplicate-fold edit loss | Verified for tested race | Outbox checks now run inside the fold transaction; emulator test edits both candidates and verifies survival/convergence. |
| S2 Incorrect file completion | Addressed in code | Missing local files are counted and pending main/attachment files produce a waiting message. Still needs actual transfer/failure tests; thumbnail placeholders are not included in this pending count. |
| S3 No OCR after download | Partial | Download completion now enqueues OCR, but process death between committing the file and enqueueing work can lose that intent. |
| O1 Silent OCR failure | Partial | Cancellation propagates and transient errors retry; unreadable input and exhausted retries still return success without a persistent visible error. |
| O2 Short and mixed-language loss | Verified for regression samples | Region/line selection passes short Chinese/Russian and mixed Hindi/Chinese and English/Tamil device cases. Broader recognition accuracy is not guaranteed. |
| O3 Worse re-read replaces good text | Not fixed | The new-script exception still accepts a drastic loss of existing text. Newly added regression fails. |
| O4 Downloading forever | Addressed in code | Failed-language state, retry action and waiting-for-internet text now exist. Exhaustion/offline recovery still needs fault-injection coverage. |
| O5 Higher PDF limit ignored | Partial | Increasing the setting requests a PDF re-read, but a worker already running keeps its old captured page limit through subsequent generations. |
| O6 Duplicate and stale OCR writes | Partial | Unique work and transactional source aggregation fix duplicate appends. Ordinary OCR and the separate re-read worker can still write stale results over newer text. |
| O7 Re-read request lost | Partial | Atomic finish fixes the original check/clear race. Restarting midway can still confuse the active request with a newer pending request. |
| O8 Unbounded PDF resources | Partial | Page slices bound each bitmap and recycle on render failure. Allocation is outside the recognition semaphore, and there is no page-level durable checkpoint. |
| O9 EXIF reflection | Verified | All eight orientation cases passed; the final image dimension cap is also enforced in code. |
| U1 Misleading messages and discoverability | Addressed in code and UI | Review/reconnect headings, review wording, privacy statement and help were updated; Settings now exposes sync/OCR near the top. Authenticated error/review UI remains untested. |

## Remaining issues and exact fixes

Paths in this section are relative to `app/src/main/java/com/ghostgramlabs/pettibox/`.

### O3 New-script detection still permits loss of existing text

**Confirmed by a failing test.** In `data/ocr/TextReader.kt:279`, `improves` returns true immediately when the new result adds a non-Latin script absent from the old text. It never applies the length/coverage check to that branch.

Reproduction: old text is an English invoice with a reference, delivery address and warranty terms; the new candidate is only `天气`; both Latin and Han are readable. The method returns true, permitting replacement of the entire invoice. This is particularly relevant to partial recognition failures or a new language pass that recognizes only one region. It is a test of replacement policy, not a claim that every invoice image produces this candidate.

The added test is [OcrRetentionRegressionTest.kt](../app/src/test/java/com/ghostgramlabs/pettibox/data/ocr/OcrRetentionRegressionTest.kt). The assertion requires rejection of the destructive replacement and currently fails.

**Fix:** do not use a newly detected script as unconditional replacement permission. Preserve accepted regions/pages and merge newly recognized regions into them. As a smaller interim change, require retention of existing readable-script coverage before replacing the whole result. Letter count alone also cannot prove keyword retention: equal-length garbage can pass it. Keep the old text available until the new result has been accepted safely.

### O1 Recognition failure still looks like success

In `data/ocr/OcrWorker.kt:55–61`, `UnreadableSourceException` is logged and returned as `Result.success()`. Other exceptions return success after the retry budget. Save and attachment entities still have text but no durable OCR error/state field. A user cannot distinguish a blank image from a failed scan or find a per-save Retry action based on this state.

**Fix:** persist Ready, No text and Failed separately, with a reason and retry eligibility. Preserve existing good text; expose Retry or Re-add file on the save. Continue propagating cancellation and retaining bounded retries, which are improvements already present.

### O5 A running re-read retains the previous PDF limit

In `data/ocr/OcrRereadWorker.kt:43`, `pages` is read once. When `finishReread` returns a newer request at line 49, the worker resets its cursor and refreshes languages but not `pages`.

**Concrete sequence:** a re-read starts at 10 pages; the user raises the limit to 30; the old worker detects the new generation and loops over the library again using 10; it then clears the cursor. The queued successor can see no pending work and exit, leaving page 20 unindexed. This sequence is established from control flow; it was not injected into a running emulator worker here.

**Fix:** capture the desired page limit in each request generation and reload it when switching generations. Add a deterministic test that changes the limit while a worker is active and verifies a page-20 keyword afterward.

### O7 A restart can consume a newer request without revisiting earlier items

`data/preferences/OcrPreferences.kt:70–73` returns the current cursor together with the latest request counter. The active generation is not persisted separately from the requested generation.

**Concrete sequence:** generation 1 reaches cursor 100; generation 2 requests all items and sets the pending-all flag; the worker is stopped before reaching the end. Its replacement loads cursor 100 but labels that work generation 2. Once it finishes older items, `finishReread(2)` sees matching counters and clears the cursor without using the pending-all flag. Items already passed above cursor 100 are not revisited for generation 2.

**Fix:** persist active-generation and requested-generation separately, including each configuration and scope. Completion must not clear a pending full pass. Test both a live request arriving during completion and interruption/restart after a request arrives. The atomic DataStore edit does fix the original narrow race, but does not by itself solve this restart case.

### O6 Result acceptance and writing are not serialized across worker types

Ordinary OCR reads the previous text, calls `improves`, and then writes (`data/ocr/OcrWorker.kt:50–52`). `OcrRereadWorker` does the same using item/attachment snapshots. `SaveRepository.setSourceText` at line 911 writes and aggregates in a transaction, but does not check whether the candidate was compared against the still-current text. The re-read worker uses a separate unique work chain from ordinary OCR.

**Remaining risk:** both workers compare against an old empty or incomplete result, the better one writes first, and the older/shorter candidate writes afterward. Unique work names only serialize ordinary jobs for that source; they do not cover this second worker.

**Fix:** give results a source/configuration version and atomically compare-and-set against the current accepted version. Alternatively route every source through one serialized processing path and revalidate acceptance in the write transaction. The replacement of append with source aggregation correctly addresses repeated-text duplication; keep that change.

### S3 Download-to-OCR scheduling is not durable

`data/sync/FileSyncWorker.kt:242` calls `readTextOf(uri)` after the transaction has replaced the placeholder. If the app process dies in that gap, the file is no longer pending on the next transfer round and the scheduling callback is not repeated. App startup reconciles language/read-cursor state, not every newly downloaded file's OCR intent.

**Fix:** record a pending OCR source/version inside the download transaction, then drain pending work idempotently after commit and on startup. Test a process stop between file commit and WorkManager enqueue. The ordinary successful-download path is now implemented correctly by inspection.

### O8 Allocation and long-work recovery remain incomplete

`TextReader.readPdf` now uses 1400 × 2000 slices instead of allocating an arbitrarily tall bitmap. That fixes the original per-page allocation defect. However, both image decoding and PDF bitmap allocation occur before entering the `gate` semaphore at `TextReader.kt:151–153`. Queued callers can already hold decoded bitmaps while waiting; the semaphore limits engine execution rather than total decoded memory.

The re-read time budget is still checked outside item processing, and a PDF's intermediate pages are not durably checkpointed. A long source can exceed the job window and restart from page one.

**Fix:** acquire a shared resource permit before decoding/rendering, and checkpoint per page/slice using the source/configuration version. Verify memory and restart behavior on a low-memory emulator. Do not equate the 11 MB slice cap with a process-wide memory cap.

## Evidence and commands

Fresh artifacts are saved separately in [recheck-evidence](recheck-evidence/) so the previous emulator results remain intact. They include the baseline unit XML files, current instrumentation XML, the failing regression XML, and a new [Settings screenshot](recheck-evidence/settings.png).

The previous opt-in `OcrAuditProbeTest` is no longer in the current tree. Its scenarios have been replaced by normal assertions in `OcrLanguagesDeviceTest`, which were run successfully. Historical JSON in `emulator-evidence` must not be mistaken for current results.

Commands used:

```powershell
# From extension/
node --test test/*.test.js

# From repository root; existing suites before adding the new regression
.\gradlew.bat :app:testDebugUnitTest --rerun :app:connectedDebugAndroidTest --offline --console=plain '-Pandroid.testInstrumentationRunnerArguments.package=com.ghostgramlabs.pettibox.data' '-Pandroid.testInstrumentationRunnerArguments.auditOcr=true'

# Newly added regression; currently fails
.\gradlew.bat :app:testDebugUnitTest --tests '*OcrRetentionRegressionTest' --offline --console=plain
```

The auditOcr argument in the broad run has no effect now that the old diagnostic class is absent. The two sample utilities were correctly skipped because their own opt-in arguments were not supplied.

The immediate priorities are O3 text preservation and O1 truthful failure state, followed by the O5/O7 re-read scheduling defects. Keep the new regression failing until the production behavior is corrected; do not weaken its assertion to restore a green build.

## Fixes after this recheck (October 17 2026, later)

Every remaining issue above was worked on. Verified on the same emulator: **100 unit tests and 82 instrumentation tests pass, 0 failures** (2 opt-in tools skipped). The O3 regression test above now passes **unchanged**.

| ID | Now | Change | Proven by |
| --- | --- | --- | --- |
| O3 | Fixed | A reading that adds a new script must have at least 60% of the old text's letters (a whole reading, not a fragment). Any other replacement must keep 90% of the letters **and** 50% of the old text's three-letter pieces (`TextReader.retained`), so same-length junk is rejected while a corrected reading ("Grocerv" → "Grocery") is accepted. | `OcrRetentionRegressionTest` (as written), `TextReaderScriptTest.junk_of_the_same_length_doesnt_replace_real_text` and the existing acceptance cases |
| O1 | Fixed | New local table `ocr_status` (schema v10, `MIGRATION_9_10`): per file Waiting / No text / Couldn't open / Couldn't read. The worker records the outcome; existing text is kept on failure. The save's detail screen says which, with **Try again** for "no text" and "couldn't read"; "couldn't open" tells the person to add the file again. | `OcrStateTest`: an unopenable file → `unreadable`; a blank image → `no_text`; text found → stored, no state. In the app: "Scan existing saves" marked two broken shares as unopenable, and the detail screen showed the message |
| O6 | Fixed | `SaveRepository.acceptSourceText` repeats the "is it better" check against the text **as it is now**, in the same transaction as the write, for ordinary OCR, re-reads and downloads alike. | `OcrStateTest.a_stale_reading_cant_overwrite_newer_text`, `reading_a_file_twice_doesnt_double_the_saves_text` |
| O5 | Fixed | The re-read loads the page limit again whenever it starts a newer round. | `OcrStateTest.raising_the_pdf_limit_reads_the_extra_pages_of_saved_pdfs`: a generated 25-page PDF read at 10 pages has no "ZEBRA" (page 20); raising the limit to 30 and running the re-read finds it |
| O7 | Fixed | The round under way (`reread_active`) is stored apart from the newest request. A job restarted midway carries on with its round; a newer request gets its own full round after. | `OcrStateTest.a_reread_restarted_midway_still_serves_a_newer_request` (stop-and-restart sequence) |
| S3 | Fixed | The downloaded file's "waiting to be read" rows are written **in the same transaction** that swaps the file in; jobs are queued after commit; app start queues any still waiting (an already queued job is left alone). | Code. The worker clearing the waiting state is covered by `a_picture_with_text_is_stored_and_leaves_no_state`; killing the process mid-download was not injected |
| O8 | Mostly fixed | The two-at-a-time permit is taken **before** decoding an image or drawing a PDF slice, so queued jobs hold no bitmaps. The re-read stops starting new saves after 4 minutes, so one long PDF finishes inside the 10-minute job window instead of restarting from page one. Still not done: a page-level checkpoint inside a single PDF longer than one job window | Code |
| S2 | As before | Thumbnail placeholders are left out of the "waiting" count on purpose: they're remade, not needed for the save to open. | — |

Also: `MigrationTest` checks v8 → v10 with Room's schema validation (the rebuilt Unicode search index finds "москва" for "Москва"; `ocr_status` exists). Installing the build over the emulator's existing v9 library migrated it to v10 with data intact. The detail screen no longer shows the vague "may still be indexing" card below a definite status.

Still untested: live Google Drive, process death injected between download and job scheduling, real camera images, TalkBack and large fonts, memory on a low-RAM device.
