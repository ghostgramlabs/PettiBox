# PettiBox sync format and merge rules

How PettiBox devices (Android phones and tablets, the Chrome extension) keep one
library in step through the user's own Google Drive. The Android app
(`app/src/main/java/com/ghostgramlabs/pettibox/data/sync/SyncModel.kt`) is the reference
implementation; `extension/sync/model.js` is a port, and both must reproduce every case in
`vectors/cases.json`.

## Drive layout

```
My Drive/PettiBox Sync/
  d-<deviceId>-<shard>.json.gz    shard 0–15; written only by that device
  files/<sha256>                  photos, PDFs and other files, named by content
```

- `deviceId`: 16 random hex characters, made once per install (or per browser profile).
- Each device publishes its **whole merged copy** of the library, split into 16
  files by `shardOf(id)` (FNV‑1a 32‑bit over the UTF‑8 bytes of the record id; the top 4
  bits). A device rewrites only the shards that changed.
- Nobody writes another device's files, so Drive never sees two writers on one file.
- A device reads every other device's shards, but only the ones whose md5 changed
  since it last read them.

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
