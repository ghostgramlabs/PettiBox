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

A reader skips a file whose `format` is higher than it knows (a newer app wrote it)
and rejects a file that fails validation, applying nothing from it.

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

## Removing unused shared files

Sync never removes a file from `files/` as part of a merge. Separately, a phone removes a
file from Drive, at most once a day, only when all of these hold:

- the last sync round, within 6 hours, read every device file without skipping one
  (damaged, or in a newer format) and left nothing waiting for review;
- no record that isn't gone uses it: not in `files`, nor as any value (concurrent ones
  included) of `main` or `thumb`; saves in Recently deleted count as using it;
- no file on the phone itself has that content;
- it has been unused for 30 days in a row (counted by that phone).

A file needed after all is uploaded again by any device that still has it; until then the
save shows it as missing. The extension never removes shared files.

## Applying a merged record on a device

- Show the shown values; trashed → in Recently deleted with `trashedAt`.
- Gone → if the save is live here, move it to Recently deleted for a fresh 30 days; sync
  never permanently deletes anything. The device's own 30-day expiry removes it.

## Guarantees (tested)

`SyncSimulationTest` runs thousands of random multi-device histories (random edits,
deletes, restores, permanent removals, random sync order, lost uploads) and checks that
devices converge, and that every value anyone wrote is either still present or was
replaced or removed by a device that had seen it. `SyncVectorsTest` keeps
`vectors/cases.json` in step with the Kotlin engine.
