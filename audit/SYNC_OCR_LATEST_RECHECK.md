# Latest sync and OCR recheck

October 17 2026. Reviewed committed revision `4c64ddb`, including implementation commit `eec3cdb`.

**Most previously open implementation gaps have been addressed, but this is not an all-clear.** The original text-retention regression now passes. An expanded regression still fails: adding a new script permits removal of an existing searchable invoice line. Automatic re-reading also does not use the new per-file error reporting, and long PDFs still cannot resume partway through a file.

This document supersedes the status conclusions of the earlier reviews. Application code was not changed during this check. One additional assertion was added to the existing OCR retention regression class.

## Fresh test results

| Check | Result |
| --- | --- |
| Extension suite | 58 passed |
| Existing Android unit suite, including the original retention regression | 100 passed |
| Android 15 API 35 emulator | 80 passed, 0 failed, 2 skipped sample utilities |
| Expanded OCR retention class, run separately afterward | 2 tests: original passed, new case failed |

The emulator run included 44 two-library sync tests, 9 sync-table tests, 19 multilingual/EXIF tests, 7 OCR-state tests and 1 database migration test. The migration test validates schema 8 through 10, Cyrillic FTS lookup, and creation of the OCR-state table. The sample clipboard/image generators were intentionally skipped.

The new OCR-state tests passed for unreadable files, blank images, successful recognition, transactional rejection of a stale short result, duplicate-free source aggregation, reading additional PDF pages, and retaining a pending request across a re-read restart. The PDF test raises the limit before starting the re-read; it does not inject a setting change into an already-running worker. Code inspection confirms that the worker now reloads the limit when moving to a newer generation.

## Status of all original issues

| ID | Current assessment |
| --- | --- |
| S1 | Fixed for the tested duplicate-fold race; integration regression passes. |
| S2 | Missing/pending main files are reported in code. Live Drive transfer failures remain unverified; thumbnail placeholders are still outside the main-file pending count. |
| S3 | Previous scheduling gap addressed in code: pending OCR rows are written in the file-download transaction and replayed on startup with unique KEEP work. A real process-kill/Drive test is still outstanding. |
| O1 | Ordinary OCR now persists No text, Unreadable and Failed, and the detail UI has matching messages/retry. Automatic re-read failures still bypass that state path; see below. |
| O2 | Short Chinese/Russian and mixed Hindi/Chinese and English/Tamil regressions pass on the emulator. |
| O3 | Original two-character replacement fixed, but partial text loss still reproduced by a new failing regression. |
| O4 | Download failure, offline waiting and retry states remain implemented. Network-failure/exhaustion UI not fault-injected in this run. |
| O5 | Higher-limit re-reading passes the PDF device test; the worker now refreshes its limit between request generations. Per-PDF searchable-page counts remain absent. |
| O6 | Previous compare/write race addressed: acceptance reads current text and writes inside the same Room transaction. Stale-short-result and duplicate-aggregation tests pass. This still depends on the imperfect O3 acceptance policy. |
| O7 | Active and requested generations are stored separately. The interrupted-generation regression passes. |
| O8 | Slice size and allocation concurrency addressed: permits are now acquired before image decoding/PDF bitmap creation. Mid-PDF durable resume remains absent. |
| O9 | All eight EXIF orientation tests pass. |
| U1 | Previous wording, status priority, privacy/help and Settings placement changes remain. Removal preview and a separate collection-management screen are still outstanding usability enhancements. |

## Confirmed remaining text-loss defect

`TextReader.improves` in `app/src/main/java/com/ghostgramlabs/pettibox/data/ocr/TextReader.kt:297` now requires a new-script result to retain at least 60 percent of the previous letter count. That branch still bypasses the content-retention check used for same-script readings.

The new regression uses:

```text
Old: Invoice reference ALPHA paid on Monday. Delivery address is London. Warranty code ZEBRA.
New: Invoice reference ALPHA paid on Monday. Delivery address is London. 天气
```

The new candidate preserves enough letters and adds Han characters, so the method accepts it even though `ZEBRA` and the warranty line disappear. The test asserts that this destructive replacement must be rejected and fails. This is a deterministic test of candidate acceptance, not a claim that the OCR engine always produces this result from invoice images.

**Fix:** preserve existing accepted regions/pages when adding another language. At minimum, do not let new-script detection bypass retention of valid existing text. The same whole-text replacement policy cannot reliably distinguish correcting a Latin misreading from deleting a real English line based only on total letter counts. Keep the original text or merge results until sufficient evidence supports replacement.

Reproduction:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OcrRetentionRegressionTest' --offline --console=plain
```

The expanded [regression class](../app/src/test/java/com/ghostgramlabs/pettibox/data/ocr/OcrRetentionRegressionTest.kt) contains both the now-passing original case and the new failing case. Do not treat the earlier 100-test green result as including this later-added assertion.

## Remaining automatic re-read error gap

`OcrWorker` writes the new status table correctly for the tested ordinary recognition paths. In contrast, `OcrRereadWorker.read` still catches recognition/read exceptions, logs them and returns null (`OcrRereadWorker.kt:94–96`). Its outer loop advances the cursor. It neither stores a failure nor schedules a retry for that source. Thus a language-triggered re-read can still silently skip a file.

Additionally, the detail UI intentionally suppresses state messages and Retry whenever any existing text is present (`DetailScreen.kt`, `ocrStatusText` and `canRetry`). A failed attempt to extend an already-partially-indexed PDF is therefore not visible through those controls even if an ordinary worker recorded the failure.

**Fix:** route re-reads through the same source-result/state handling as ordinary OCR, with bounded retry and accepted-text preservation. Show failure of the latest attempted scan separately from availability of older text. These remaining paths were identified by inspection; they were not fault-injected on the emulator in this run.

## Long PDFs and remaining usability work

Memory admission now happens before allocation, addressing the earlier semaphore placement problem. PDF slices are bounded, but progress is still only retained at the item cursor level. `RUN_MS` was shortened to four minutes to leave more room for a long source; that does not checkpoint a PDF's completed pages. A stopped job can still restart the same file from page one. Add durable page/slice progress keyed by source/configuration version and verify on a long PDF under interruption.

The following are still useful product work, distinct from the reproduced text-loss defect:

- Show how many PDF pages are searchable.
- Preview saves affected by a large removal/archiving review.
- Move collection administration to its own destination.

## Evidence and limits

Fresh XML results are saved in [latest-recheck-evidence](latest-recheck-evidence/). `retention-expanded.xml` records the two-case targeted run and its failure; the original baseline XML remains separate.

All testing used the connected Android emulator. Sync integration uses an in-memory Drive transport, not live Google Drive. No cloud account was connected. Actual process-death recovery during download, exhausted model-download retries, real camera-image accuracy and low-memory long-PDF profiling remain unverified. Passing the targeted tests supports closing their specific regressions, not declaring every reliability path complete.

## Fixes after this recheck (October 17 2026, later)

The three remaining functional issues are fixed. Verified on the same emulator: **102 unit tests and 85 instrumentation tests pass, 0 failures** (2 opt-in tools skipped). Both cases in `OcrRetentionRegressionTest`, including the expanded invoice/`ZEBRA` case, pass **unchanged**.

| Issue | Change | Proven by |
| --- | --- | --- |
| Adding a script could drop existing text | `TextReader.improves` no longer has a new-script shortcut or letter-count rules. A new reading replaces old text only if **every old word** (3+ letters or digits) is accounted for: still present, present with a small correction (≤ 1 edit per 4 letters, e.g. "Grocerv" → "Grocery"), or seen by the engines **this time at the same place and dropped for a better line** (`Reading.replaced`: Latin lines under a chosen language line, overlapping losers, and in-script lines too unsure to keep). Without that evidence an old word may be real, so the old text stays. Text in a script that can't be read now still always stays. | `OcrRetentionRegressionTest` (both cases); `TextReaderScriptTest` (`every_old_word_must_be_accounted_for`, same-length junk, fewer pages, corrections); on the emulator `OcrStateTest.a_latin_misreading_is_replaced_once_its_language_is_turned_on`: a Russian picture first read with Latin only gets its Russian text once Russian is on, because the reader reports the dropped Latin guess |
| Automatic re-read failures skipped silently | Both workers read through one path, `SourceReader`, which records no text / couldn't open / couldn't read (last try) and keeps existing text. A re-read failure that isn't final is handed to an ordinary `OcrWorker` (backoff, 3 tries, then "couldn't read"). The detail screen shows the latest attempt's failure **even when older text exists** ("The latest reading … failed. The text below is from before." + Try again), and a PDF being read shows pages so far. | `OcrStateTest.a_reread_that_cant_open_a_file_records_it`; the earlier worker-state tests |
| Long PDFs couldn't resume midway | Progress is saved after every page on the file's `ocr_status` row (schema v11, `MIGRATION_10_11`: `config`, `pages_done`, `partial_text`, `partial_replaced`; state `reading`). A reading resumes from the next page when the file, languages and page limit are the same. The re-read pauses between pages when its time budget is used and the next job carries on; WorkManager reschedules a stopped ordinary job, which resumes; app start queues part-read files. Nothing is stored as the save's text until the PDF is whole. | `OcrStateTest.a_long_pdf_stopped_midway_carries_on_from_its_next_page`: stopped after 5 of 25 pages (state `reading`, 5 pages done, no text stored); the saved partial text is marked; the next run's result contains the mark and page 20's "ZEBRA", so it carried on rather than starting over. `MigrationTest` validates 8 → 11 |

Still not covered: killing the process during a real download, real camera images and scans, TalkBack and large fonts, a low-memory device. The usability items (searchable-page counts, a preview in the bulk-removal review, a separate collection-management screen) are unchanged.
