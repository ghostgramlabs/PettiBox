"""Manage PettiBox's Google Play store listings from store/play-listing/.

    python -X utf8 tools/store/play_listing.py check            # local limits + keyword report (offline)
    python -X utf8 tools/store/play_listing.py diff             # compare with the live listings (read-only)
    python -X utf8 tools/store/play_listing.py push             # upload to a draft, let Google validate, discard
    python -X utf8 tools/store/play_listing.py push --commit    # same, then save it (goes to review / live)

Options for diff/push: --only de-DE,fr-FR to limit the languages.

Auth: Application Default Credentials that impersonate a service account with Play Console
access, so tokens are short-lived and no key file is ever created. Set up once with:
    gcloud auth application-default login \
        --impersonate-service-account=<service-account>@<project>.iam.gserviceaccount.com
Never download a service account JSON key for this; .gitignore blocks them anyway.

Play only exposes listings through an "edit" (a draft). diff and push without --commit always
delete the draft, so nothing changes on the store.
"""
import argparse
import json
import pathlib
import sys

PACKAGE = "com.ghostgramlabs.pettibox"
ROOT = pathlib.Path(__file__).resolve().parents[2] / "store" / "play-listing"
LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
FILES = {"title": "title.txt", "shortDescription": "short_description.txt",
         "fullDescription": "full_description.txt"}


def load_local():
    listings = {}
    for d in sorted(p for p in ROOT.iterdir() if p.is_dir()):
        listings[d.name] = {f: (d / n).read_text(encoding="utf-8").strip() for f, n in FILES.items()}
    return listings


def check(listings):
    kw_file = ROOT / "keywords.json"
    keywords = json.loads(kw_file.read_text(encoding="utf-8")) if kw_file.exists() else {}
    errors = 0
    for loc, l in listings.items():
        sizes = []
        for f, lim in LIMITS.items():
            n = len(l[f])
            sizes.append(f"{n}/{lim}")
            if n > lim:
                errors += 1
                print(f"ERROR {loc}: {f} is {n} characters, limit {lim}")
            if not l[f]:
                errors += 1
                print(f"ERROR {loc}: {f} is empty")
        if "PettiBox" not in l["title"]:
            errors += 1
            print(f"ERROR {loc}: title does not contain the app name")
        kws = keywords.get(loc)
        if not kws and ":" in l["title"]:
            # No keyword list: the search phrase after "App:" in the title is the primary keyword.
            kws = [l["title"].split(":", 1)[1].strip()]
        report = ""
        if kws:
            low = {f: v.lower() for f, v in l.items()}
            primary = kws[0].lower()
            counts = [low["fullDescription"].count(k.lower()) for k in kws]
            if primary not in low["title"] and primary not in low["shortDescription"]:
                print(f"WARN  {loc}: primary keyword '{kws[0]}' is in neither title nor short description")
            if counts[0] < 3:
                print(f"WARN  {loc}: primary keyword '{kws[0]}' appears only {counts[0]}x in the full description")
            if counts[0] > 10:
                print(f"WARN  {loc}: primary keyword '{kws[0]}' appears {counts[0]}x, may read as keyword stuffing")
            for k, c in zip(kws, counts):
                if c == 0:
                    print(f"WARN  {loc}: keyword '{k}' missing from the full description")
            report = "  kw " + " ".join(f"{k}={c}" for k, c in zip(kws, counts))

        print(f"{loc:6} title {sizes[0]:>5}  short {sizes[1]:>5}  full {sizes[2]:>9}{report}")
    print(f"\n{len(listings)} languages, {errors} error(s)")
    return errors


def service():
    import google.auth
    from googleapiclient.discovery import build
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()


def live_listings(edits, edit_id):
    res = edits.listings().list(packageName=PACKAGE, editId=edit_id).execute()
    return {l["language"]: l for l in res.get("listings", [])}


def diff(local, only):
    edits = service()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    try:
        live = live_listings(edits, edit_id)
    finally:
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
    for loc in sorted(set(local) | set(live)):
        if only and loc not in only:
            continue
        if loc not in local:
            print(f"{loc:6} live only (not in store/play-listing, left untouched by push)")
        elif loc not in live:
            print(f"{loc:6} NEW: not on Play yet")
        else:
            changed = [f for f in FILES if local[loc][f] != (live[loc].get(f) or "").strip()]
            print(f"{loc:6} " + ("changed: " + ", ".join(changed) if changed else "same as live"))
    print("(read-only: draft deleted, nothing changed)")


def push(local, only, commit):
    edits = service()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    saved = False
    try:
        live = live_listings(edits, edit_id)
        todo = [loc for loc in local if (not only or loc in only)
                and any(local[loc][f] != (live.get(loc, {}).get(f) or "").strip() for f in FILES)]
        for loc in todo:
            body = {"language": loc, **local[loc]}
            edits.listings().update(packageName=PACKAGE, editId=edit_id, language=loc, body=body).execute()
            print(f"{loc:6} {'updated' if loc in live else 'added'} in draft")
        if not todo:
            print("Nothing differs from the live listings.")
            return
        edits.validate(packageName=PACKAGE, editId=edit_id).execute()
        print(f"Google validated the draft ({len(todo)} language(s)).")
        if commit:
            edits.commit(packageName=PACKAGE, editId=edit_id).execute()
            saved = True
            print("COMMITTED: changes are saved and go through Play review.")
    finally:
        if not saved:
            edits.delete(packageName=PACKAGE, editId=edit_id).execute()
            print("Dry run: draft deleted, nothing changed on Play. Add --commit to save.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["check", "diff", "push"])
    ap.add_argument("--only", help="comma-separated Play language codes")
    ap.add_argument("--commit", action="store_true", help="push: save the draft instead of discarding it")
    a = ap.parse_args()
    only = set(a.only.split(",")) if a.only else None
    local = load_local()
    if a.command == "check":
        sys.exit(1 if check(local) else 0)
    if check(local):
        sys.exit("Fix the errors above first.")
    if a.command == "diff":
        diff(local, only)
    else:
        push(local, only, a.commit)


if __name__ == "__main__":
    main()
