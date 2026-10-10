# PettiBox sync format and merge rules

How PettiBox devices (Android phones and tablets, the Chrome extension) keep one
library in step through the user's own Google Drive. The Android app
(`app/src/main/java/com/ghostgramlabs/pettibox/data/sync/SyncModel.kt`) is the reference
implementation; `extension/sync/model.js` is a port, and both must reproduce every case in
`vectors/cases.json`.

## Drive layout

```
My Drive/PettiBox/
  Backups/                        nightly backup zips (not part of sync)
  Sync/
    d-<deviceId>-<shard>.json.gz  shard 0–15; written only by that device
    pettibox-sync-reset.json      only after "Delete synced data" (see below)
    files/<sha256>.<ext>          photos, PDFs, files and card pictures, named by content
```

Everything PettiBox keeps in Drive is under the one `PettiBox` folder. Finding a folder:
take the oldest folder of that name the app can see (drive.file shows only folders PettiBox
made); if there are more, move the others' contents into it and delete them (two devices
may create one at the same moment). Older versions used top-level `PettiBox Sync` and
`PettiBox Backups`; those are moved into `PettiBox` and renamed `Sync` / `Backups`, and if
one appears again (an older app version on another device), its contents are moved in.
`DriveFolders.kt` and `extension/drive.js` implement this.

- `deviceId`: 16 random hex characters, made once per install (or per browser profile).
- Each device publishes its **whole merged copy** of the library, split into 16
  files by `shardOf(id)` (FNV‑1a 32‑bit over the UTF‑8 bytes of the record id; the top 4
  bits). A device rewrites only the shards that changed.
- Nobody writes another device's files, so Drive never sees two writers on one file.
- A device reads every other device's shards, but only the ones whose md5 changed
  since it last read them.

### Deleting synced data

"Delete synced data from Google Drive" writes `pettibox-sync-reset.json` into the
folder (`{"format":1,"resetAt":…,"by":"<device>"}`), then deletes everything else in it.
Each device notes the marker's *content* when it starts syncing (an empty string if there's
none) and compares on every round: if it changed, the device stops syncing, keeps its own
library, and uploads nothing. Starting sync again notes the marker afresh and re-uploads
that device's whole copy. Comparing content rather than timestamps keeps a wrong clock
from mattering.

## Device file

gzip of UTF‑8 JSON:

```json
{"format": 1, "device": "9f2c…", "name": "Pixel 8", "shard": 3, "generated": 1760000000000,
 "records": [ <record>, … ]}
```

A reader skips a file whose `format` is higher than it knows (a newer app wrote it) and
says so ("another device uses a newer version"), and rejects a file that fails validation,
applying nothing from it. Validation, the same in both apps:

- `shard` is 0–15 and matches the file name; at most 200,000 records; at most 256 MB unpacked;
  no record twice.
- Every time (`c`, `vv`, `x`, `gone`, dot `t`) is a whole number from 0 to 2^53−1.
- Every dot is covered by its record's `vv` (`t ≤ vv[d]`); nothing is dated over 10 years ahead.
- Known fields have their type (null always allowed): text — title, url, notes, ocr, meta,
  cat, type, src, main, thumb, name, emoji, parent, `ocr:<sha>`; true/false — fav, pin,
  arch, user, locked; numbers — remind, opened, trash, color, order. Unknown fields may hold
  any plain value and pass through.
- File keys are lowercase hex; ids and tag keys at most 200 characters.

`epoch` (left out when empty) is the reset marker the file was written under. A reader
skips a file whose epoch isn't the marker it knows, so a device that wrote just as someone
chose "Delete synced data" can't bring the old library back. Writers check the marker again
before each file they send.

## Record

```json
{
  "k": "item",                         // "item" (a save) or "col" (a collection)
  "id": "3f9c1e77a24b4d0e8b6a5c2f10d4e881",
  "c": 1756000000000,                  // created; merge keeps the smallest
  "vv": {"<dev>": <ts>, …},            // causal context: newest write seen per device
  "f": {                               // fields: name -> concurrent values
    "title": [{"v": "How sourdough works", "t": 1756000000123, "d": "<dev>"}],
    "trash": [{"v": 1756100000000, "t": 1756100000000, "d": "<dev>", "x": {<vv at delete>}}]
  },
  "tags": {"bread": [{"n": "Bread", "t": …, "d": …}]},           // lowercase key -> adds
  "att":  {"<sha256>": [{"k": "IMAGE", "o": 0, "t": …, "d": …}]},  // file -> adds
  "gone": {<vv at permanent removal>}                             // only once removed
}
```

A **write** is identified by its dot `(d, t)`: device id and a hybrid-clock timestamp
(`t = max(now, newest timestamp ever seen + 1)`), unique per device. Values are strings,
whole numbers, booleans or null.

### Item fields

| field | value | notes |
|---|---|---|
| `title`, `url`, `notes` | string / null | |
| `ocr`, `ocr:<sha256>` | string / null | recognized text; *derived* |
| `meta` | string / null | link details; *derived* |
| `cat` | collection id / null | |
| `type`, `src` | string | content type, source app |
| `fav`, `pin`, `arch` | boolean | |
| `remind`, `opened` | ms / null | `opened` is *derived* |
| `trash` | ms / null | in Recently deleted since; carries `x` |
| `main` | sha256 / null | the save's own file |
| `thumb` | sha256 / null | its card picture (link preview, page screenshot); *derived* |

Collection fields: `name`, `emoji`, `color`, `order`, `parent`, `user`, `locked`.
Unknown fields are kept and merged like any other, so a newer app's fields pass through
older devices untouched.

## Merge

For every field, tag and file key, with `A` and `B` the two copies:

> keep an element of A if B has it too, or if `B.vv` does not cover its dot;
> and the same the other way round.

`covers(vv, dot) = (vv[dot.d] ?: 0) >= dot.t`. An element one copy has seen but dropped
was replaced or removed there on purpose. `vv` merges by taking each device's max,
`gone` likewise; `c` takes the min. Elements are kept sorted by dot `(t, d)`.

If both copies carry the same dot with *different* contents (only possible when a file
is corrupt), keep the one with the smaller **tie key** (string order): for a field value
`"h:" + v + "|" + ctx`, where `v` is `null`, `s:<string>`, `n:<number>` or
`b:<true|false>` and `ctx` is the `x` context as `dev=ts` pairs sorted by device and
joined with `,` (or `-` when absent); for a tag `"t:" + name`; for a file
`"f:" + kind + "|" + order`.

This is commutative, associative and idempotent, so devices converge whatever order
they sync in.

## Reading a record

- **Shown value** of a field: the value with the greatest dot. Other values with a
  different value are *alternatives*: the field was edited on two devices without
  either seeing the other, and both are kept until someone picks one (any new write of
  the field replaces all values it has seen).
- **Tags / files present**: keys with at least one add. Display name, kind and order come
  from the newest add.
- **User dots**: dots of every field except the derived ones (`ocr`, `ocr:*`, `meta`,
  `opened`, `thumb`), plus every tag and file add.
- **Gone** (permanently removed): `gone` is set and covers every user dot.
- **Trashed** (in Recently deleted): the shown `trash` value is non-null and its `x`
  covers every user dot outside `trash`. An edit the deleting device hadn't seen brings
  the save back.

## Local edits (outbox)

A device folds its current state of a record (`LocalRecord`: every field it knows,
present tags, present files) into its merged copy with one new dot:

1. Not in the copy: write every field, tag and file.
2. Missing locally (permanently removed here) and not already gone: set
   `gone = max(gone, vv ∪ {me: t})`.
3. Copy is gone but the save is here: if it is in Recently deleted here, nothing (it was
   moved there because of the removal); otherwise it was restored: write everything.
4. Otherwise write each field whose local value differs from the *shown* value (an open
   conflict stays open), add tags/files not present, remove ones no longer local
   (`trash` compares with the trashed-at value in effect).
5. If anything was written and the record carries a `gone` that isn't in effect, write
   everything; if it carries a non-null `trash` that isn't in effect, also write
   `trash = null`. Either way, a save that an unseen edit brought back stays back even if
   that edit is later undone.

## The same link saved twice

Two live saves with the same link (compared like `TextUtils.urlDedupeKey`: scheme and host
in lower case) are folded together when they were created on **different** devices (origin =
the device of the earliest `type` write); two saves of a link made on one device were made
on purpose and stay. The save that stays is the oldest (`c`, then `id`). It takes what only
the other has: notes are joined with a blank line unless one contains the other; a missing
title, collection, reminder, file, picture or text is taken; favorite and pinned if either
is; it leaves Archive or Recently deleted if the other is live; tags and files are added.
The other save is then removed (`gone`), so on a phone that had it, it waits in Recently
deleted. Every device computes the same result. `SyncDedupe` (Kotlin) and `fold`/`foldPlan`
(extension) implement it; `vectors/cases.json` has golden `fold` cases.

## Sending and fetching shared files

Files under 5 MB go up in one request. Bigger ones use a Drive resumable upload in 8 MiB
pieces; the session address is kept until the upload ends, so a later try asks Drive how far
it got and sends only the rest (a session Drive no longer knows starts a fresh upload).
Downloads keep what arrived as `<sha256>.part` and ask for the rest with a `Range` request;
the whole file must still hash to its name before it is used, or the partial copy is dropped.

## Removing unused shared files

Sync never removes a file from `files/` as part of a merge. Separately, a phone moves a
file to Drive's trash (never deletes it), at most once a day, only when all of these hold:

- the last sync round, within 6 hours, read every device file without skipping one
  (damaged, or in a newer format) and left nothing waiting for review; any later round that
  fails clears this;
- just before, no other device's file has changed since that round (else the next round
  reads it first), and nothing waits in this phone's inbox or outbox;
- no record that isn't gone uses it: not in `files`, nor as any value (concurrent ones
  included) of `main` or `thumb`; saves in Recently deleted count as using it;
- no file on the phone itself has that content;
- it has been unused for 30 days in a row (counted by that phone).

Drive keeps trashed files for 30 days. A phone that needs a file that isn't listed looks in
the trash and takes it back out, so a save restored or edited elsewhere just before the
clean-up still gets its file; any device that still has the file also uploads it again.
This narrows the race between "unused" and "used again" but isn't a full distributed
deletion protocol: a device offline for more than 30 + 30 days can find a file gone. The
extension never removes shared files.

Folders: two devices can each make a PettiBox, Sync or files folder at the same moment. The
oldest wins; the others' contents move into it (same-named sub-folders are merged, not put
side by side), and an emptied duplicate goes to Drive's trash only if a fresh look still
finds it empty. Devices check that their remembered folder isn't trashed before each round.

Transfers: one transfer per file at a time across a phone's jobs, so two never share a
partial download or an upload session.

## Applying a merged record on a device

- Show the shown values; trashed → in Recently deleted with `trashedAt`.
- Gone → if the save is live here, move it to Recently deleted for a fresh 30 days; sync
  never permanently deletes anything. The device's own 30-day expiry removes it.
- A reference to a record this device doesn't have yet (a save's collection, a collection's
  parent) is left unset locally, never written back as "none": a device's 16 files are read
  as they come, so a save can arrive before its collection. When that collection arrives,
  every save and sub-collection whose merged copy names it is filed under it.
- An edit made on the device while incoming records are being applied is diffed against the
  copy it was based on and merged in, inside the same transaction, so it can't be overwritten.

## Accounts, clocks and follow-ups

- **Account:** a library syncs with the Google account it first synced with. A round (and
  the file job) that finds Drive signed in as another account reads and writes nothing and
  says so. Turning sync off ends the binding; turning it on again with another account
  syncs this library into that one, on purpose.
- **Clock:** before writing records, a phone saves a clock ceiling (now or the clock,
  whichever is later, plus the number of writes and a minute). A phone that dies mid-round
  therefore never stamps a new edit below one it already wrote. First-sync stamps are never
  later than now.
- **Reviews:** a "Keep mine / Apply" choice is tied to the set of saves it was shown for. If
  more removals arrive before it's used, the user is asked again. While a review waits,
  this device's own changes are still sent.
- **Safety copies:** the first-sync copy is retried until it works; a mass removal is not
  applied without one.
- **Follow-ups:** saves a round applies are noted before applying; their reminders and link
  pictures are set up again on the next round if the app stopped first.
- **Missing own files:** a device whose own files are gone from Drive (removed by hand)
  sends them again, unless the library was reset.

- **Privacy:** everything synced is readable in the user's own Drive (gzip JSON, not
  encrypted). Locked collections are hidden in the extension, not encrypted. Records removed
  for good keep their last field values in their tombstone.

## Device identity

A device id belongs to one install. Android copies app settings in a phone-to-phone transfer,
so the app also keeps its id in a no-backup file; settings whose id doesn't match that file
are a copy, which takes a fresh id and publishes all 16 files under it. (Installs from before
the no-backup file existed are marked on their first round.)

## Guarantees (tested)

`SyncSimulationTest` runs thousands of random multi-device histories (random edits,
deletes, restores, permanent removals, random sync order, lost uploads) and checks that
devices converge, and that every value anyone wrote is either still present or was
replaced or removed by a device that had seen it. `SyncVectorsTest` keeps
`vectors/cases.json` in step with the Kotlin engine.
