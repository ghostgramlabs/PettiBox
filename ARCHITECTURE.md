# PettiBox architecture

This guide describes PettiBox's features, architecture and implementation details, so a new
contributor can find their way around the code. Sections 1 to 27 were reviewed in full for version 2.0.7, and
section 28 covers what 2.0.8 to 2.0.12 added. Where the guide and the code disagree, the code is right, and a pull request fixing the guide is
welcome. For build steps see the [README](README.md).

## 1. Product Overview

PettiBox is an offline-first Android app for saving and finding personal content later. Users can save:

- Links and URLs
- Text and quick notes
- Highlighted/selected text from other apps
- Images and screenshots
- PDFs and files
- Tags, notes, collections, favorites, pins, archive state, and reminders
- OCR text extracted from images and PDFs

The main product idea is: save from anywhere, organize lightly, and find from memory later.

PettiBox does not use a GhostGram Labs backend for saved content. The app is built around local storage, local search, local reminders, and local backups.

## 2. Build And Platform

Main Gradle config: `app/build.gradle.kts`

Current stack:

- Kotlin
- Jetpack Compose
- Material 3
- Room
- Room FTS4
- Paging 3
- Hilt
- WorkManager
- DataStore Preferences
- Coil
- Google ML Kit Text Recognition
- Jsoup
- Android AlarmManager
- Android Storage Access Framework

Important build settings:

- `applicationId = "com.ghostgramlabs.pettibox"`
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 24`
- Java/Kotlin target 17
- Release minification and resource shrinking enabled
- Release signing is read from `local.properties` or environment variables:
  - `PETTIBOX_RELEASE_STORE_FILE`
  - `PETTIBOX_RELEASE_STORE_PASSWORD`
  - `PETTIBOX_RELEASE_KEY_ALIAS`
  - `PETTIBOX_RELEASE_KEY_PASSWORD`

Useful commands:

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug bundleRelease --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

## 3. Android Manifest And App Entry Points

Manifest: `app/src/main/AndroidManifest.xml`

Declared permissions:

- `INTERNET`
  - Used for link metadata and preview image fetching.
- `POST_NOTIFICATIONS`
  - Used for reminder notifications on Android 13+.
- `SCHEDULE_EXACT_ALARM`
  - Used for time-sensitive reminders.
- `RECEIVE_BOOT_COMPLETED`
  - Used to re-arm reminders after reboot, package replacement, time changes, and timezone changes.

Application flags:

- `android:allowBackup="false"`
- `android:fullBackupContent="false"`

This disables Android automatic app-data backup. PettiBox uses its own explicit backup/export system.

Activities:

- `MainActivity`
  - Main launcher activity.
  - Hosts the Compose app.
  - Applies the PettiBox theme.
  - Handles reminder deep links into the Detail screen.

- `ShareReceiverActivity`
  - Transparent share-target activity.
  - Receives Android share intents.
  - Supports text, images, PDFs, generic files, multiple images, and selected text.
  - Opens `SaveSheet`.
  - Finishes after save or dismiss so it does not remain in recents.

Providers:

- `FileProvider`
  - Used to share exported backup files and local files safely.

- AndroidX Startup provider with WorkManager initializer removed
  - WorkManager is configured through Hilt.

Receivers:

- `ReminderAlarmReceiver`
  - Receives AlarmManager reminder alarms and notification snooze actions.

- `BootCompletedReceiver`
  - Re-schedules pending reminders after reboot, package replacement, time changes, and timezone changes.

## 4. Dependency Injection

Hilt is used across ViewModels, WorkManager workers, receivers, repository, and utilities.

App module: `di/AppModule.kt`

Provides:

- Room database
- DAOs
- Repository dependencies

App class: `PettiBoxApp`

Responsibilities:

- Initializes Hilt app.
- Configures WorkManager with `HiltWorkerFactory`.
- Creates notification channel on startup.
- Seeds default categories.
- Reconciles automatic backup WorkManager schedule from preferences.
- Re-schedules pending reminders on cold start.

## 5. Navigation

Navigation file: `ui/nav/NavGraph.kt`

Routes:

- `home`
- `search?q={q}&src={src}`
- `detail/{id}`
- `categories?cid={cid}`
- `settings`

Top tabs:

- Home
- Search
- Browse
- Settings

Important behavior:

- Bottom navigation is custom Compose UI, not stock `NavigationBar`.
- Selected tab has accessibility semantics.
- Reminder notification deep links set an item id on `MainActivity`; `NavGraph` observes it and navigates to Detail once the `NavController` exists.
- Top-level navigation resets to start destination to avoid deep stacks when switching tabs.

## 6. Data Layer

Database: `data/local/AppDatabase.kt`

Room database version: `6` (exported schemas for every version are in `app/schemas/`; a schema
change needs a version bump and a migration)

Entities:

- `SaveItemEntity`
- `SaveItemFts`
- `CategoryEntity`
- `AttachmentEntity`
- `TagEntity`
- `ItemTagCrossRef`
- `ArticleCopyEntity` and `ArticleCopyFts`: offline copies of link articles (see section 28)

### SaveItemEntity

File: `data/local/SaveItemEntity.kt`

Represents one saved item.

Fields:

- `id`
- `title`
- `url`
- `localUri`
- `thumbnailUri`
- `contentType`
- `sourceApp`
- `categoryId`
- `notes`
- `ocrText`
- `metadataJson`
- `isFavorite`
- `isPinned`
- `isArchived`
- `remindAt`
- `createdAt`
- `updatedAt`
- `openedAt`

Important behavior:

- `isArchived` is a soft-delete/safety-net state.
- Archived saves disappear from Home and normal Browse views.
- Archived saves can still be reachable in Archive.
- `remindAt` is epoch millis for reminder time.
- When reminders fire, `remindAt` is cleared.

Indexes:

- category
- created time
- source app
- content type
- favorite
- pinned
- opened time
- archived state
- reminder time
- category plus created time
- source plus created time

### CategoryEntity

File: `data/local/CategoryEntity.kt`

Represents a collection/shelf.

Fields:

- `id`
- `name`
- `emoji`
- `colorHex`
- `sortOrder`
- `parentId`
- `userCreated`
- `createdAt`

Built-in collections are seeded from `CategoryPalette.Defaults`. User-created collections can be created, edited, and deleted. Deleting a collection keeps saves in the app; Room foreign key behavior sets `category_id` to null.

### AttachmentEntity

File: `data/local/AttachmentEntity.kt`

Represents a file/image/PDF attached to a save.

Fields:

- `id`
- `itemId`
- `uri`
- `kind`
- `ocrText`
- `sortOrder`
- `createdAt`

Attachments cascade-delete when parent save is deleted.

### Tags

Files:

- `data/local/TagEntity.kt`
- `data/local/TagDao.kt`

Tags are normalized into:

- `tags`
- `item_tags`

Tag names are unique case-insensitively.

Benefits:

- tag filtering uses indexed joins
- known tag aggregation does not require scanning every item row

### FTS Search

File: `data/local/SaveItemFts.kt`

Room FTS4 is used for saved item search. Search content includes saved item fields and extracted OCR text.

`SearchQuerySanitizer` sanitizes raw user text before FTS:

- trims input
- removes quotes and punctuation
- keeps letters/numbers
- drops tokens shorter than 2 characters
- appends `*` wildcard for prefix matching

## 7. DAO Layer

Main DAO: `data/local/SaveDao.kt`

Important query groups:

- Insert/update/delete/get save items
- Duplicate detection by exact live URL
- Home lists:
  - recent
  - pinned
  - favorites
- Paged browse:
  - all
  - by category
  - by source
  - favorites
  - archive
  - by tag
  - upcoming reminders
- Aggregates:
  - source counts
  - category counts
  - recent category IDs
  - total live count
  - archived count
  - favorite count
  - upcoming reminder count
- Reminder queries:
  - due reminders
  - pending reminders
- Mutations:
  - favorite
  - pinned
  - archived
  - remindAt
  - openedAt
  - OCR text
  - append OCR text atomically
- FTS search

Large browse screens use PagingSource instead of loading all rows.

## 8. Repository Layer

Main repository: `data/repository/SaveRepository.kt`

Responsibilities:

- Provides a single API over Room DAOs.
- Seeds default categories.
- Inserts/updates/deletes save items.
- Deletes files associated with saves/attachments.
- Provides Home/Browse/Search flows and PagingSource factories.
- Sets favorite/pinned/archive/reminder flags.
- Handles tags.
- Handles attachments.
- Exports and imports backups.

Permanent delete behavior:

- Cancels reminder outside repository in ViewModels.
- Repository deletes database row.
- Room cascades attachments and tag links.
- Repository deletes stored attachment files using `AttachmentStore`.

Archive behavior:

- Archive is a soft state (`isArchived = true`).
- Archive generally cancels reminders so users do not get nudged about tucked-away items.

Backup behavior:

- JSON export schema 1 is supported.
- ZIP export schema 2 embeds:
  - `backup.json`
  - file entries under `files/`
- ZIP import:
  - reads `backup.json`
  - ingests embedded files into app storage
  - guards against zip-slip
  - remaps old save IDs to new save IDs
  - remaps old tag IDs to new tag IDs
  - runs inside a Room transaction

## 9. Domain Models

### ContentType

File: `domain/model/ContentType.kt`

Enum:

- `LINK`
- `IMAGE`
- `TEXT`
- `PDF`
- `FILE`
- `NOTE`

`fromMime()` maps MIME type to content type.

### SourceApp

File: `domain/model/SourceApp.kt`

Enum source labels and emojis:

- Instagram
- Reddit
- YouTube
- Chrome
- Maps
- WhatsApp
- X/Twitter
- Pinterest
- Spotify
- Amazon
- Files
- Other

`fromUrl()` detects source based on URL host.

### CategoryPalette

File: `domain/model/CategoryPalette.kt`

Default collections include:

- Recipes
- Travel
- Fitness
- Style
- Home
- Beauty
- Read Later
- Finance
- Music
- Books
- Food Spots
- Shopping
- Work
- Ideas
- Watch
- Learning
- Health
- Docs
- Personal
- Tech
- Design
- Gifts
- Places
- Kids

Each has id, name, emoji, and color.

## 10. Saving Flow

### Android Share Parsing

File: `ui/screens/save/IncomingShare.kt`

Incoming share data:

- `text`
- `urls`
- `imageUris`
- `fileUris`
- `mimeType`

Supported Android actions:

- `Intent.ACTION_SEND`
- `Intent.ACTION_SEND_MULTIPLE`
- `Intent.ACTION_PROCESS_TEXT`

The parser extracts:

- subject
- shared text
- selected process text
- URLs with regex
- stream URIs
- image vs file based on MIME type

### Share Receiver

File: `ui/screens/save/ShareReceiverActivity.kt`

Behavior:

- Receives share intent.
- Converts to `IncomingShare`.
- If empty, finishes.
- Shows `SaveSheet` over transparent activity.
- Finishes on save or dismiss.
- If duplicate save banner opens existing item, launches `MainActivity` with item ID.

### Save Sheet ViewModel

File: `ui/screens/save/SaveSheetViewModel.kt`

State includes:

- mode: new or pick existing
- title
- preview image
- description
- notes
- tags input
- source app
- content type
- URL
- local URI
- attachments
- favorite state
- reminder time
- selected category
- suggested category
- categories
- recent category IDs
- recent items
- duplicate item
- resolving metadata state
- saved state

Ingest behavior:

1. Detect first URL.
2. Collect image/file URIs.
3. Determine content type.
4. Detect source app from URL.
5. Generate smart title.
6. Set initial state.
7. If URL exists:
   - query duplicate live save
   - fetch metadata with `MetadataFetcher`
   - update title/description/preview image
   - update category suggestion
8. If no URL:
   - still suggest category based on text/title once categories load

Category suggestion:

- User-created collection name match wins.
- Otherwise URL/title/source heuristics map to default category IDs.
- Suggestions are only highlighted, not auto-selected.

Saving:

1. Copy foreign `content://` URIs into app-owned storage.
2. Insert `SaveItemEntity`.
3. Schedule reminder if set.
4. Parse and store tags.
5. Insert attachments.
6. Enqueue OCR/PDF OCR if auto scan enabled.
7. Mark state as saved.

Save to existing:

- Adds current share as attachments to existing save.
- Appends notes/URL to existing notes.
- Enqueues OCR for new attachments.

## 11. Link Metadata

File: `data/metadata/MetadataFetcher.kt`

Uses Jsoup.

Behavior:

- Fetches Open Graph / Twitter metadata.
- Extracts:
  - title
  - description
  - preview image
  - site name
- Has timeout so Save flow does not hang.
- Uses YouTube thumbnail fallback for YouTube URLs.

Network note:

- Website receives normal HTTP connection metadata like IP/user-agent.
- PettiBox does not use this for tracking.

## 12. OCR And PDF Text Extraction

### OCR Preferences

File: `data/preferences/OcrPreferences.kt`

Stores:

- auto scan enabled/disabled
- PDF page limit

### Image OCR

File: `data/ocr/OcrWorker.kt`

Uses:

- WorkManager
- HiltWorker
- ML Kit Text Recognition

Behavior:

- Input contains item id, optional attachment id, and URI.
- Uses `InputImage.fromFilePath`.
- If OCR text is found:
  - writes to attachment OCR if attachment id exists
  - atomically appends text to parent item OCR field
  - otherwise writes directly to item OCR field
- OCR failures are treated as best-effort success to avoid retry loops.

### PDF OCR

File: `data/ocr/PdfTextWorker.kt`

Uses:

- `PdfRenderer`
- Bitmap rasterization
- ML Kit OCR per rendered page
- PDF page limit from preferences

Behavior:

- Opens PDF through content resolver.
- Renders each page up to limit.
- Runs OCR per page.
- Stores combined text on item or attachment.
- Uses target page width to bound memory.

## 13. Search

Files:

- `ui/screens/search/SearchScreen.kt`
- `ui/screens/search/SearchViewModel.kt`

Search state:

- query
- source filter
- category filter
- type filter
- tag filter
- reminder filter
- results
- categories
- known tags
- sources
- sort

Search behavior:

- Debounces query by 180 ms.
- If query is nonblank, uses FTS search.
- If only filters are active, uses capped browse candidates.
- If neither query nor filters are active, returns no results and shows suggestions.
- Applies filters in memory on candidate list:
  - source
  - category
  - content type
  - upcoming reminders
  - tag ID membership
- Supports sort:
  - relevant
  - newest
  - oldest
  - recently edited
  - reminder time

Search UI features:

- Empty state prompts.
- Quick suggestions for tags/sources/types/reminders.
- Filter chips.
- Sort strip.
- Result cards.
- Long-press quick actions.
- Delete uses archive-first Undo flow.

## 14. Browse / Collections

Files:

- `ui/screens/categories/CategoriesScreen.kt`
- `ui/screens/categories/CategoriesViewModel.kt`

Browse destinations:

- Grid
- Category drill
- Favorites
- Archive
- Reminders
- Tag list
- Specific tag

Route wire format:

- normal category id
- `__fav`
- `__arc`
- `__rem`
- `__tags`
- `__tag:<name>`

Browse state:

- destination
- categories
- counts by category
- favorite count
- archived count
- reminder count
- top tags
- show archived toggle
- sort

Paged streams:

- Favorites
- Archive
- Reminders
- Category
- Tag

Grid features:

- Collections
- Favorites tile
- Archive tile
- Reminders tile
- Tags entry

Drill features:

- Sort strip
- Staggered card grid
- Per-item category color in cross-collection views
- Edit/delete user-created collections
- Per-category archived toggle
- Multi-select
- Bulk archive/delete

Multi-select behavior:

- Entered with visible `Select` control.
- Tapping saves selects/unselects them.
- Long-press remains reserved for quick actions outside selection mode.
- Three-dot card action remains quick actions outside selection mode.
- Selection mode hides the quick-action button to avoid gesture conflicts.

## 15. Home

Files:

- `ui/screens/home/HomeScreen.kt`
- `ui/screens/home/HomeViewModel.kt`

Home observes:

- recent saves
- pinned saves
- favorite saves
- categories
- source counts
- total live save count
- archived count
- OCR indexing state
- notification-blocked state
- onboarding state
- backup restore candidate

Home UI features:

- Onboarding dialog
- Backup restore prompt on empty/fresh app state
- Notification warning banner if reminders cannot post notifications
- OCR indexing status
- Recent/pinned/favorites sections
- Source chips
- Category browse section
- Add floating action button
- Add chooser for note/link/image/file
- Save sheet
- Long-press quick actions

Backup restore prompt:

- Checks latest backup in PettiBox backup folder.
- Only shown if app has no live or archived saves.
- User can Import or Skip.
- Import runs backup ZIP import.
- Restored reminders are rescheduled.
- Handled backup filename is stored so prompt does not repeat.

Onboarding:

- Explains:
  - keep things for later
  - share into PettiBox
  - find it fast
  - archive as safety net
- Final CTA opens quick note flow.

## 16. Detail Screen

Files:

- `ui/screens/detail/DetailScreen.kt`
- `ui/screens/detail/DetailViewModel.kt`

Detail state:

- loaded state
- item
- category
- all categories
- attachments
- tags
- OCR auto scan setting

Behavior:

- Touches `openedAt` on init.
- Edit title.
- Edit notes.
- Set collection.
- Add/remove tags.
- Toggle favorite.
- Toggle pinned.
- Archive/unarchive.
- Set/clear reminder.
- Delete item with archive-first Undo.
- Delete attachments with Undo-like staged behavior.
- View/share/open/copy content depending on type.
- Navigate from tag to Browse tag view.

## 17. Cards And Quick Actions

### SaveCard

File: `ui/components/SaveCard.kt`

Visual variants:

- Image: polaroid card
- Link: bookmark card
- Note/Text: sticky note
- PDF/File: paperclip card

Card metadata:

- title
- notes preview
- source stamp
- category emoji/name
- relative time
- favorite marker
- pinned marker
- reminder pill
- archive pill

Interaction:

- tap opens detail
- long-press opens quick actions
- three-dot button opens quick actions

### QuickActionSheet

File: `ui/components/QuickActionSheet.kt`

Actions:

- pin/unpin
- favorite/unfavorite
- archive/unarchive
- reminder
- move to collection
- delete

Delete UX:

- Copy explains PettiBox moves item to Archive first and gives Undo.
- Archive is framed as safety net.

## 18. Archive And Delete Model

Across Home, Search, Browse, and Detail:

1. User chooses Delete.
2. App stages delete by setting `isArchived = true`.
3. App clears/cancels reminder if present.
4. Snackbar shows `Moved to Archive` with `Undo`.
5. If user taps Undo:
   - archive state restored to original
   - reminder restored if it was still in future
6. If snackbar expires:
   - item is permanently deleted
   - reminder is canceled
   - files are cleaned up

This protects users from accidental deletion while still allowing permanent cleanup.

## 19. Reminders

Files:

- `data/reminders/ReminderScheduler.kt`
- `data/reminders/ReminderAlarmReceiver.kt`
- `data/reminders/BootCompletedReceiver.kt`
- `data/reminders/ReminderNotifications.kt`
- `ui/components/ReminderPicker.kt`

Reminder scheduling:

- Uses AlarmManager.
- Exact alarm if allowed.
- Falls back to `setAndAllowWhileIdle` if exact alarm access is unavailable.
- One pending intent per save item id.
- Re-scheduling cancels old alarm first.

Reminder firing:

- Receiver checks item still exists.
- Ignores stale alarm if `remindAt` changed.
- Ignores/cancels archived item reminders.
- Posts notification.
- Clears `remindAt`.

Notification actions:

- Open item.
- Snooze 1 hour.
- Snooze to this evening based on configured evening time.

Re-scheduling:

- App start calls `ReminderScheduler.rescheduleAll`.
- Boot receiver re-arms reminders after reboot/time/timezone/package changes.
- Past-due reminders are scheduled shortly after startup/boot so they are not lost.

Reminder settings:

- Morning preset time.
- Evening preset time.
- Exact alarm permission helper.
- Notification blocked warning.

## 20. Backups And Restore

Files:

- `data/backup/LocalBackupWorker.kt`
- `data/backup/BackupSummaryCalculator.kt`
- `data/util/LocalBackupStore.kt`
- `data/preferences/BackupPreferences.kt`
- backup methods in `SaveRepository`
- backup controls in Settings

Manual export:

- Creates a ZIP backup in cache.
- Shares via FileProvider.

Manual restore:

- User picks JSON or ZIP backup through Android picker.
- ZIP imports embedded files.
- JSON imports metadata-only backup.

Automatic local backup:

- User enables in Settings.
- WorkManager daily worker runs around 2 AM.
- Requires battery not low.
- Network not required.
- Creates local ZIP backup.
- Keeps recent copies.
- Optionally copies to user-selected folder.
- Records copy failure if SAF folder copy fails.

Fresh install restore:

- Home checks PettiBox backup folder for latest backup.
- If app is empty and backup has not been handled, asks user to restore.
- User confirms Import or Skip.
- App only auto-checks its own backup folder.
- Backups elsewhere are restored manually from Settings.

Backup contents:

- categories
- saves
- attachments
- tags
- tag links
- favorites
- pinned state
- archived state
- reminders
- OCR text
- embedded local files when available

Import safety:

- Zip-slip guard rejects unsafe paths.
- All import DB writes are in a transaction.
- IDs are remapped instead of reused.

## 21. Settings

Files:

- `ui/screens/settings/SettingsScreen.kt`
- `ui/screens/settings/SettingsViewModel.kt`

Settings features:

- Theme mode:
  - system
  - light
  - dark
- OCR:
  - enable/disable automatic scan
  - scan existing saves
  - PDF page limit
- Reminders:
  - morning time
  - evening time
  - exact alarm permission education/action
- Collections:
  - create collection
  - edit user-created collection
  - delete user-created collection
- Backups:
  - automatic safety copy
  - last backup confidence banner
  - private backup path
  - choose/change backup folder
  - make safety copy now
  - export backup file
  - restore from backup
- Help content explaining Save/Search/Archive/Backup behavior.

## 22. Preferences

DataStore preference classes:

- `ThemePreferences`
  - stores selected theme mode

- `OcrPreferences`
  - auto scan enabled
  - PDF page limit

- `ReminderPreferences`
  - notifications blocked flag
  - morning reminder time
  - evening reminder time

- `BackupPreferences`
  - automatic local backup enabled
  - last backup timestamp/name
  - picked backup folder URI
  - last copy failure timestamp

- `OnboardingPreferences`
  - home onboarding completed
  - backup restore prompt handled file

## 23. Theme And Design System

Files:

- `ui/theme/Theme.kt`
- `ui/theme/Color.kt`
- `ui/theme/Shape.kt`
- `ui/theme/Type.kt`
- `ui/theme/PaperTexture.kt`

Design identity:

- PettiBox uses a hand-picked palette, not Material You dynamic colors.
- Light mode is persimmon on bone/paper.
- Dark mode is persimmon on warm charcoal.
- App-wide paper texture is drawn behind screens.
- Screens generally use transparent Scaffold backgrounds.
- Custom bottom dock navigation.
- Cards use tactile paper metaphors:
  - polaroid
  - bookmark
  - sticky note
  - paperclip document
- Collection chips use emoji/color and slight hand-arranged feel.
- Mascot PNGs are used for onboarding, empty states, reminders, backup, OCR, and error states.

Accessibility work already present:

- Bottom nav exposes selected tab semantics.
- Category chips expose role/selected/state description.
- Selected chip text color adjusts for contrast on bright category colors.

## 24. Privacy-Relevant Behavior

PettiBox stores saved content locally.

Network use:

- Link metadata fetching only.
- Website may see normal request info.

No GhostGram Labs backend:

- No account required.
- Saved content is not uploaded to a GhostGram Labs server.

Permissions:

- Internet for metadata.
- Notifications for reminders.
- Exact alarm for reminder timing.
- Boot completed to re-arm reminders.
- File/media picker/share sheet for user-selected content.
- SAF folder access only if user chooses backup folder.

Backups:

- Backup files may contain saved content and attachments.
- User controls export/share/copy destinations.
- Automatic local backup is opt-in.
- PettiBox checks only its own backup folder automatically for restore prompts.

## 25. Tests

Test files:

- `app/src/test/java/com/ghostgramlabs/pettibox/domain/model/ContentTypeTest.kt`
- `app/src/test/java/com/ghostgramlabs/pettibox/data/repository/SearchQuerySanitizerTest.kt`
- `app/src/test/java/com/ghostgramlabs/pettibox/data/backup/BackupSummaryCalculatorTest.kt`
- `app/src/test/java/com/ghostgramlabs/pettibox/ui/components/ReminderPresetTest.kt`

Coverage:

- MIME to content type mapping
- FTS query sanitization
- Backup summary counts
- Reminder preset time calculation

## 26. Important UX Decisions

- Suggested collection is highlighted but not auto-selected.
- Archive is the default safety net before permanent delete.
- Bulk select is explicit and does not hijack long-press.
- Long-press and three-dot remain quick action entry points.
- Search supports discovery even when user does not remember exact title.
- Reminder permission education appears near reminder flows/settings.
- Backup wording explains automatic folder limits and manual restore.
- Onboarding explains the app in user terms:
  - keep things for later
  - share into PettiBox
  - find it fast

## 27. Potential Areas For Future Improvement

Possible next steps:

- Add Compose UI tests for save sheet and search filters.
- Add Room migration tests.
- Add backup import/export round-trip tests using test database.
- Add instrumentation tests for share intents.
- Add manual QA checklist for TalkBack, large font, dark mode, low contrast, and notification permissions.
- Consider a clearer visual affordance for `Select` mode in Browse.
- Consider explicit restore preview before import, showing saves/attachments count from backup summary.
- Consider user-controlled backup encryption if backups may contain sensitive content.

## 28. Added in 2.0.8 to 2.0.12

Paths are under `app/src/main/java/com/ghostgramlabs/pettibox/`.

| Feature | Where | How it works |
| --- | --- | --- |
| Offline article copies | `data/article/` (`ArticleExtractor`, `ArticleRepository`, `ArticleCopyWorker`), `data/local/ArticleCopyEntity.kt`, `ArticleDao.kt`, `data/preferences/ArticlePreferences.kt` | A WorkManager job, constrained to having a network connection, extracts a link's article text into `ArticleCopyEntity` (searchable through `ArticleCopyFts`). A failed refresh keeps the existing copy. Shown in `ui/screens/detail/ArticleReader.kt`. |
| Local link thumbnails | `data/metadata/ThumbnailWorker.kt` | Downloads a link's preview image as a small JPEG, so cards keep their picture offline. |
| Image zoom and PDF reader | `ui/screens/detail/Zoomable.kt`, `PdfViewer.kt`, `FullScreenDialog.kt` | Pinch-zoom for images and an in-app PDF viewer built on Android's `PdfRenderer`. |
| App lock | `ui/lock/AppLock.kt`, `data/preferences/AppLockPreferences.kt` | Process-wide lock state. The app starts locked, relocks after the chosen time in the background, and locks immediately when the screen turns off. Unlocking uses the system biometric/PIN prompt. |
| Unread widget | `ui/widget/ShelfWidget.kt`, `WidgetTitlesExpiryWorker.kt`, `res/xml/shelf_widget_info.xml` | A Glance widget listing unopened saves. With App lock on, it shows titles only while the app is unlocked; a worker hides them again at the relock deadline. |
| Weekly nudge | `data/reminders/ShelfNudgeWorker.kt` | Once a week, at the user's morning reminder time, a notification resurfaces one save that was never opened, avoiding recently nudged ones. On by default; needs notification permission. |
| App shortcuts | `res/xml/shortcuts.xml`, `ui/nav/AppLaunch.kt` | Launcher shortcuts (new link, new note, search, unread) routed into navigation. |
| Multi-file saves and local restore | share parsing and backup code (sections 10 and 20) | Shares with several files are accepted and typed by content; backups kept on the phone can be restored directly. |
| Review prompt | `ui/components/ReviewPrompt.kt` | In-app Google Play review prompt. |
