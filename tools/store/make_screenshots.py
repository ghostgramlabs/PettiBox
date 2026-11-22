"""Build PettiBox's Play phone screenshots (1080x1920) in every store language, and upload them.

    python -X utf8 tools/store/make_screenshots.py render [--only en-US,hi-IN]
    python -X utf8 tools/store/make_screenshots.py upload [--only ...] [--commit]

Inputs: store/screenshots-raw/NN_name.png (1080x2400 emulator captures, light theme, demo status bar)
and store/screenshot-captions.json (a [headline, sub line] pair per screenshot, per Play language).
Output: build/store-screenshots/<Play language>/NN.png (not committed; regenerate any time).

Each screenshot is a small HTML page rendered by headless Microsoft Edge: a warm PettiBox background,
a serif headline in the app's style, a sub line, and the capture in a phone frame. Edge shapes every
script correctly (Arabic, Indic, CJK). Long headlines shrink so they never reach the phone.
upload replaces each language's phone screenshots on Play (a dry run unless --commit).
"""
import argparse
import html
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = ROOT / "store" / "screenshots-raw"
CAPTIONS = ROOT / "store" / "screenshot-captions.json"
OUT = ROOT / "build" / "store-screenshots"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PACKAGE = "com.ghostgramlabs.pettibox"
ALIASES = {"es-ES": "es-419", "es-US": "es-419"}
W, H = 1080, 1920

CJK = {"ja-JP": '"Yu Gothic UI", "Yu Gothic"', "ko-KR": '"Malgun Gothic"',
       "zh-CN": '"Microsoft YaHei UI"', "zh-TW": '"Microsoft JhengHei UI"'}
SANS = '"Segoe UI", "Nirmala UI", "Leelawadee UI", "Yu Gothic UI", "Malgun Gothic", "Microsoft YaHei UI", sans-serif'
SERIF = 'Georgia, "Segoe UI", "Nirmala UI", "Yu Gothic UI", "Malgun Gothic", "Microsoft YaHei UI", serif'

PAGE = """<!doctype html><html lang="{lang}" dir="{dir}"><head><meta charset="utf-8"><style>
html,body{{margin:0;width:{w}px;height:{h}px;overflow:hidden}}
body{{background:
  radial-gradient(1100px 760px at 100% 0%, rgba(226,94,58,.20), transparent 62%),
  radial-gradient(900px 700px at 0% 100%, rgba(168,138,104,.22), transparent 60%),
  linear-gradient(180deg,#f6efe4 0%,#efe3d3 100%);
  font-family:{sans}}}
#t{{position:absolute;left:70px;right:70px;top:84px;text-align:center}}
#h{{font-family:{serif};font-weight:700;font-size:78px;line-height:1.12;color:#2b211c;letter-spacing:-.5px;text-wrap:balance}}
#s{{margin-top:16px;font-size:38px;line-height:1.3;color:#6d5a4c;text-wrap:balance}}
#p{{position:absolute;left:50%;transform:translateX(-50%);border-radius:74px;background:#231c18;padding:16px;
  box-shadow:0 40px 80px rgba(70,45,25,.30),0 8px 24px rgba(70,45,25,.18)}}
#p img{{display:block;border-radius:60px;width:{sw}px}}
</style></head><body>
<div id="t"><div id="h">{head}</div><div id="s">{sub}</div></div>
<div id="p"><img src="{img}"></div>
<script>
const t=document.getElementById('t'),h=document.getElementById('h'),s=document.getElementById('s'),p=document.getElementById('p');
let k=1;const maxT={maxt};
while(t.offsetHeight>maxT&&k>0.6){{k-=0.04;h.style.fontSize=(78*k)+'px';s.style.fontSize=(38*Math.max(k,.8))+'px';}}
const py=84+t.offsetHeight+40; p.style.top=py+'px';
// the phone takes the space left, up to {sw}px wide, always leaving 50px at the bottom
const img=p.querySelector('img'); img.style.width=Math.min({sw},({h}-50-py-32)*1080/2400)+'px';
</script></body></html>"""


def edge_shot(page, out):
    with tempfile.TemporaryDirectory() as d:
        src = pathlib.Path(d, "p.html")
        src.write_text(page, encoding="utf-8")
        for _ in range(3):  # Edge occasionally hangs on start-up; a fresh run always works
            try:
                subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                                "--force-device-scale-factor=1", f"--window-size={W},{H}",
                                f"--screenshot={out}", src.as_uri()],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45)
                if pathlib.Path(out).exists():
                    return
            except subprocess.TimeoutExpired:
                continue
    sys.exit(f"Edge could not render {out}")


def render(only):
    caps = json.loads(CAPTIONS.read_text(encoding="utf-8"))
    raws = sorted(RAW.glob("*.png"))
    langs = [l for l in caps if not l.startswith("_")] + list(ALIASES)
    for lang in langs:
        if only and lang not in only:
            continue
        items = caps[ALIASES.get(lang, lang)]
        if len(items) != len(raws):
            sys.exit(f"{lang}: {len(items)} captions for {len(raws)} screenshots")
        out_dir = OUT / lang
        out_dir.mkdir(parents=True, exist_ok=True)
        fonts = (CJK[lang] + ", " if lang in CJK else "")
        for n, (raw, (head, sub)) in enumerate(zip(raws, items), 1):
            page = PAGE.format(lang=lang, dir="rtl" if lang == "ar" else "ltr", w=W, h=H, sw=660,
                               sans=fonts + SANS, serif=fonts + SERIF, head=html.escape(head),
                               sub=html.escape(sub), img=raw.as_uri(), maxt=290)
            target = out_dir / f"{n:02d}.png"
            target.unlink(missing_ok=True)
            edge_shot(page, str(target))
        print(f"{lang}: {len(items)} screenshots")


def upload(only, commit):
    import google.auth
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    edits = build("androidpublisher", "v3", credentials=creds, cache_discovery=False).edits()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    saved = False
    try:
        for lang_dir in sorted(p for p in OUT.iterdir() if p.is_dir()):
            lang = lang_dir.name
            if only and lang not in only:
                continue
            files = sorted(lang_dir.glob("*.png"))
            edits.images().deleteall(packageName=PACKAGE, editId=edit_id, language=lang,
                                     imageType="phoneScreenshots").execute()
            for f in files:
                edits.images().upload(packageName=PACKAGE, editId=edit_id, language=lang, imageType="phoneScreenshots",
                                      media_body=MediaFileUpload(str(f), mimetype="image/png")).execute()
            print(f"{lang}: {len(files)} uploaded to draft")
        edits.validate(packageName=PACKAGE, editId=edit_id).execute()
        print("Google validated the draft.")
        if commit:
            edits.commit(packageName=PACKAGE, editId=edit_id).execute()
            saved = True
            print("COMMITTED: screenshots are saved and go through Play review.")
    finally:
        if not saved:
            edits.delete(packageName=PACKAGE, editId=edit_id).execute()
            print("Dry run: draft deleted, nothing changed on Play. Add --commit to save.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["render", "upload"])
    ap.add_argument("--only", help="comma-separated Play language codes")
    ap.add_argument("--commit", action="store_true")
    a = ap.parse_args()
    only = set(a.only.split(",")) if a.only else None
    render(only) if a.command == "render" else upload(only, a.commit)


if __name__ == "__main__":
    main()
