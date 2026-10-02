# PettiBox emulator QA report

Test date: October 2, 2026 (America/Chicago). App: **2.0.10 / versionCode 25**, debug build, commit **63215c9**. Google Drive authentication, upload, download, and restore were excluded.

## Result

Found **7 functional findings**, a lint quality-gate failure with **2 errors**, and performance concerns requiring release-build/device validation. This is a broad exploratory audit, not a claim of exhaustive coverage of every device, input, and lifecycle combination. Product source was not modified.

Highest priorities: correct URL deduplication, remove the search candidate cap before filtering/sorting, refresh search after writes, clean up files from failed/duplicate restores, and repair the save sheet during rotation.

## Environments and evidence

| Environment | Usage | Limitations |
|---|---|---|
| Pixel_5_API_36, Android 16, 1080×2340 | Share, save, search, OCR, PDF, reminders, themes, deletion, local backup, startup | Existing app data retained; emulator disconnected during later navigation workload |
| Medium_Phone_API_35, Android 15, 1080×2400 | Fresh onboarding, import/restore, collections, font scaling, rotation, navigation performance | Headless software graphics (`swiftshader_indirect`); initial System UI unresponsiveness and poor rendering performance |

Android 16's device clock showed October 1 while the client date was October 2. Reminder comparisons used the device clock. Android 15 used October 2. These clock differences are not an app defect.

UI hierarchy XML, screenshots, logs, statistics, and fixtures are under [evidence](evidence/) and [fixtures](fixtures/). Read-only database inspection included its WAL, and the temporary database copy was removed. The audit left test records and fixtures in the emulators. Network access and font scale were restored; rotation was returned to automatic. The Android 15 emulator remains running with the app open. No external messages were sent.

## Findings

### B01 — P1: valid links are dropped when their paths differ only by case

**Confirmed in the Android 15 UI and database audit.**

1. Settings → Import bookmarks file.
2. Select [qa-case-urls.csv](fixtures/qa-case-urls.csv), containing `https://example.com/CaseSensitive` and `https://example.com/casesensitive`.
3. Observe `Imported 1 link, 1 already saved`.

Expected: both distinct URL paths are retained. Actual: only one URL is present. The entire URL is lowercased for deduplication; path/query case can distinguish resources. The same normalization appears in backup restore, so that path needs correction too (restore-specific case loss was not separately exercised).

Location: `SaveRepository.kt:728` (bookmark import), `:584` and `:616` (backup restore). Normalize the scheme/host appropriately while preserving path/query distinctions.

Evidence: [state-audit.json](evidence/state-audit.json), case-import tool output, fixture above.

### B02 — P1: filters and oldest sorting miss records beyond the newest 200

**Source inspection plus an executed SQL reproduction; not a large-library emulator test.**

1. Have an older image plus 205 newer links.
2. Search with no query and an image/type filter.
3. The DAO returns the newest 200 records first; the ViewModel applies the image filter afterward.

Expected: the image appears. Actual reproduced result: zero images. Oldest sorting also operates on the newest candidate subset rather than the whole matching library. Text search separately has a hard limit of 200, without pagination to reveal remaining matches.

Locations: `SaveDao.kt:73`, `:437`; candidate loading and post-filtering in `SearchViewModel.kt`. Push filtering/sorting into database queries and page the complete result set.

Evidence: [search-limit-reproduction.json](evidence/search-limit-reproduction.json). Rerun with `python qa/reproduce_search_limit.py`.

### B03 — P2: an open search stays stale after a new save

**Confirmed in the Android 16 UI.**

1. Search for `QA_NOTE_20261002`; observe no results.
2. Share that exact text into PettiBox, choose Books, and save.
3. Return promptly to the still-open search.
4. It continues to show `Nothing matched`.
5. Clear and re-enter the identical query; the saved item appears.

Expected: writes refresh current results. Actual: candidates are loaded by a one-shot suspend query on query/filter changes. This also creates a source-level risk of stale results after title edits, deletion, and OCR completion; those variants were not independently verified.

Location: `SearchViewModel.kt` candidate `flow { emit(...) }` and repository suspend search/browse methods. Observe database invalidation or explicitly refresh after writes.

Evidence: [search-saved.xml](evidence/search-saved.xml) versus [refreshed-search.xml](evidence/refreshed-search.xml).

### B04 — P2: failed and duplicate local restores leak attachment files

**Confirmed in the Android 15 UI, filesystem, and database audit.**

Failed restore reproduction:

1. Restore [qa-missing-manifest.zip](fixtures/qa-missing-manifest.zip), which contains a small image under `files/` but no `backup.json`.
2. Confirm Restore.
3. The app reports `That backup file couldn't be imported`.
4. A new **27,392-byte** PNG remains in internal `files/attachments`, with no save/attachment reference.

Duplicate restore reproduction:

1. Restore the valid local ZIP captured from the Android 16 test.
2. Restore it again; the app reports nothing added because all saves already exist.
3. A second **19,617-byte** file is still created and has no database reference.

Expected: failure and duplicate skipping do not leave unreferenced copies. Actual: files are copied before manifest validation/deduplication; the `finally` block only removes article temporary files. Repeated restores can consume storage even when no saves are added.

Location: `SaveRepository.kt:488–540`, followed by duplicate skipping in the import transaction. Track newly created files and remove unused copies on failure or skipped rows.

Evidence: [orphan-files-before.txt](evidence/orphan-files-before.txt), [orphan-files-after.txt](evidence/orphan-files-after.txt), [state-audit.json](evidence/state-audit.json), [orphan-restore-failed.xml](evidence/orphan-restore-failed.xml).

### B05 — P2: rotation makes the note save sheet inaccessible

**Confirmed visually on Android 15, default font scale, Gboard.**

1. Open a quick note, with its keyboard visible.
2. Rotate to landscape.
3. Type `QA_ROTATION_DRAFT`.
4. Observe the editor/Save controls obscured by the keyboard; only the sheet header/finder remains clearly visible.
5. Rotate back to portrait. The sheet moves out of view while the keyboard remains over Home; Back exits the sheet.

Expected: the draft, editor, and save action remain reachable through rotation. Actual: unusable sheet geometry; draft recovery requires further verification. Do not treat this as proven loss of a previously saved item.

Review `SaveSheet.kt` fixed fractional heights, pinned header/footer and IME padding, plus `HomeScreen.kt:155` transient `remember` state.

Evidence: [rotated-note.png](evidence/rotated-note.png), [landscape-typed.xml](evidence/landscape-typed.xml), [rotation-draft-settled.png](evidence/rotation-draft-settled.png).

### B06 — P2: manual recovery cannot directly select the app's automatic local copy

**Observed UI gap, corroborated by source.**

1. Settings → Back up now (Google Drive disconnected, no extra folder).
2. Settings → Restore a backup.
3. Only `From Google Drive` and `From a file` are offered.
4. The file picker does not list the automatic copy; it resides in `/sdcard/Android/data/com.ghostgramlabs.pettibox/files/Documents/pettibox-backups/`.

Expected: an explicit list of the retained local copies, allowing recovery while keeping existing saves. The automatic Home recovery prompt only appears when both active and archived counts are zero. The portable-file restore itself works: this audit used ADB to copy the local ZIP into Downloads, then successfully restored it through the picker.

Locations: `LocalBackupStore.kt:72`, Settings restore chooser, and `HomeScreen.kt` `shouldOfferBackupRestore`. Add a direct local-copy option backed by `LocalBackupStore`, including the three retained files.

Evidence: [restore-picker.xml](evidence/restore-picker.xml), [backup-file-picker.xml](evidence/backup-file-picker.xml), [local-backups](evidence/local-backups/).

### B07 — P3: empty quick notes can be saved

**Confirmed in the Android 16 UI and restored-data audit.**

1. Home → + → Note.
2. Enter no text, title, or attachment.
3. Tap Save without collection.

Actual: a blank NOTE titled `Quick save` is inserted. Expected: disable Save or show validation until there is meaningful content. This adds empty records to the shelf and backups.

Location: `SaveSheetViewModel.kt:397` lacks a meaningful-content gate. Evidence: [note-empty.xml](evidence/note-empty.xml), [after-empty-save.xml](evidence/after-empty-save.xml), `empty_notes: 1` in [state-audit.json](evidence/state-audit.json).

### Q01 — quality gate: Android lint fails

Command: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`.

Assembly and all 33 unit tests succeeded. Lint failed with **2 errors and 74 warnings**:

- `MainActivity.kt:51`: `FlowOperatorInvokedInComposition`.
- `ShareReceiverActivity.kt:44`: the same error.

Both construct an `enabled.map` flow inside Compose rendering. Move/memoize the transformation outside repeated composition. The warnings include outdated Navigation lint registries whose checks were skipped; 74 warnings does not mean 74 confirmed user-facing bugs.

Evidence: [lint-results-debug.txt](evidence/lint-results-debug.txt); full HTML at `app/build/reports/lint-results-debug.html`.

## Performance observations

| Measurement | Result | Interpretation |
|---|---|---|
| Initial Android 16 launch after emulator start/install | Activity displayed after 18.380 s; `am start -W` timed out | Boot/install warmup interference; not a steady-state launch figure |
| Five later Android 16 cold starts | 6027, 2140, 2144, 2129, 2198 ms; median **2144 ms** | Consistent ~2.1–2.2 s after the first sample; debug emulator only |
| Android 16 graphics during exploratory use | 732 frames, 242 janky (**33.06%**); p50 19 ms, p90 48 ms, p95 150 ms | Mixed workload, including launch; no controlled baseline |
| Android 16 memory snapshot | PSS 138,288 KB (~135 MiB) | Single snapshot; does not establish a leak |
| Android 15 navigation workload | 20 tab taps + 10 swipes over 22.13 s; 363 frames, 359 janky (**98.90%**); p50 77 ms, p95 350 ms | Software-rendered environment was very slow; cannot attribute all jank to app code |
| Android 15 memory after navigation | PSS 159,753 KB (~156 MiB), swap PSS 75,906 KB (~74 MiB) | Significant emulator paging; not a before/after leak diagnosis |

No PettiBox `FATAL EXCEPTION`, `ANR in`, or `OutOfMemoryError` appeared in the retained log samples. Android 15 initially reported **System UI** unresponsiveness, and Android 16 disconnected during one workload. Neither is classified as a proven app crash. Zero-byte files from the disconnected workload are invalid measurements; the `api35-*` files contain the completed rerun.

Next performance validation: profile a release/profileable build on a physical midrange device with a representative library, separately measuring cold start, scrolling, search, OCR, and backup. Do not use these debug/software-emulator percentages as release performance guarantees.

Evidence: [startup-repeated.txt](evidence/startup-repeated.txt), [gfx-before.txt](evidence/gfx-before.txt), [memory-before.txt](evidence/memory-before.txt), [api35-gfx-navigation.txt](evidence/api35-gfx-navigation.txt), [api35-memory-after-navigation.txt](evidence/api35-memory-after-navigation.txt), [api35-navigation-workload.json](evidence/api35-navigation-workload.json).

## Coverage

| Area/scenario | Result |
|---|---|
| Debug build/install on Android 15 and 16 | Passed |
| Fresh onboarding Next pages and quick-note entry | Passed |
| Home, Search, Browse, Settings navigation | Exercised; poor emulator rendering noted |
| Empty quick-note save | B07 |
| Typed note save | Passed; database confirms large-font and collection test notes |
| Shared plain text | Passed |
| Assign existing collection at save time | Passed |
| Create collection and save into it | Passed |
| Pin and favorite setting | Passed; changed-state labels observed |
| Link metadata/title resolution | Passed for Example Domain |
| Duplicate-link banner | Passed |
| Save URL without network | Passed |
| Search a saved note | Passed after refreshing; B03 |
| Search older filtered data | B02, source/SQL reproduction only |
| Image selection/import and permanent local copy | Passed |
| OCR image text search | Passed for `ALBATROSS` |
| Scanned PDF import and OCR search | Passed; image and PDF both matched |
| PDF preview and fullscreen page view | Passed for one-page fixture |
| Delete confirmation and Recently deleted placement | Passed |
| Restore from Recently deleted | Passed; empty bin afterward |
| Reminder notification-permission request | Passed |
| Reminder permission denial/dismissal | No crash; no reminder persisted in the exercised dismissal |
| Tomorrow reminder after permission grant | Passed; UI and AlarmManager entry confirmed |
| Reminder cancellation | Passed |
| Custom reminder with past time | Passed; validation text shown and Set disabled |
| Light/dark appearance switching | Passed |
| Automatic local backup via Back up now | Passed |
| Local ZIP restore across Android versions | Passed; 4 saves, 24 collections, 1 tag restored |
| Restore on existing shelf confirmation | Passed |
| Duplicate restore records | Passed; no new saves, but B04 file leak |
| Invalid/empty backup file | Error displayed; no crash |
| ZIP missing manifest | Error displayed; B04 file leak |
| HTML bookmark import/folder collection | Passed; 2 links, 1 new collection |
| CSV import with case-sensitive URL pair | B01 |
| 200% font scaling, note entry/save | Passed in portrait; database confirms note |
| Landscape note entry/portrait return | B05 |
| Force-stop/cold restart and stored notes | Exercised; database audit confirms persistence |
| Navigation memory/frame collection | Completed on Android 15; environment caveats above |

### Existing automated tests

33 tests across 8 suites passed: BackupSummaryCalculator (1), BookmarkCsvWriter (3), BookmarkFileParser (10), NetscapeBookmarkParser (9), RealExportSamples (3), SearchQuerySanitizer (2), ContentType (1), ReminderPreset (4). These cover parser and helper behavior, not end-to-end UI correctness.

### Coverage still required before claiming full validation

- Physical-device and release-build performance/battery profiling; sustained memory/leak testing.
- Android 7–14 compatibility, OEM differences, tablet/foldable layouts, RTL/locales, TalkBack and full accessibility audit.
- Complete title/note/tag editing, collection rename/color/delete, bulk actions, archive/unarchive, permanent delete, timed Undo.
- Multi-file/mixed-MIME shares, ACTION_PROCESS_TEXT, rapid successive shares, revoked URI permissions, very large/corrupt/encrypted PDFs and other file types.
- Actual reminder firing, notification actions/snooze/deep links, exact-alarm grant/revoke, Doze, reboot, timezone/DST/clock changes.
- App-lock authentication, relock grace period, widget privacy, notification redaction and recent-app previews (emulators have no configured credentials for this audit).
- Widget installation/interactions, offline article reader/retry/zoom, clipboard edge cases and other external app integrations.
- Scheduled overnight backup, three-copy retention, selected-folder permission revocation, low disk/storage failure, backup export/share round trips with all attachment types.
- Real 200+/10,000-item emulator libraries, process death mid-import, and long-running stress tests.
- Google Drive remains deliberately excluded.

## Reusable audit artifacts

- `ui.py`: ADB UI hierarchy/tap/screenshot helper; target serial is in `device.txt`.
- `create_fixtures.py`: small deterministic image/PDF/bookmark fixtures.
- `reproduce_search_limit.py`: executable candidate-cap reproduction.
- `navigation_performance.py`: fixed Android 15 portrait navigation workload (assumes the tested dimensions).
- `audit_state.py`: reads a stopped debug app's DB+WAL, outputs limited QA counts/references, removes temporary DB copies.

The UI helpers record evidence but are not a complete assertion-based regression suite. The earlier `performance.py` workload encountered emulator disconnection; use the completed navigation workload results instead.
