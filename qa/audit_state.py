import subprocess,sqlite3,pathlib,json
root=pathlib.Path(__file__).parent/'evidence'
serial=(root.parent/'device.txt').read_text().strip()
pkg='com.ghostgramlabs.pettibox'
def adb(*args):
    r=subprocess.run(['adb','-s',serial,*args],capture_output=True)
    if r.returncode: raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout
adb('shell','am','force-stop',pkg)
dbpath=root/'audit-state.db'
dbpath.write_bytes(adb('exec-out','run-as',pkg,'cat','databases/pettibox.db'))
for suffix in ['-wal','-shm']:
    r=subprocess.run(['adb','-s',serial,'exec-out','run-as',pkg,'cat','databases/pettibox.db'+suffix],capture_output=True)
    if r.returncode==0: pathlib.Path(str(dbpath)+suffix).write_bytes(r.stdout)
db=sqlite3.connect(dbpath)
references=set()
for a,b in db.execute('SELECT local_uri,thumbnail_uri FROM save_items'):
    references.update(x.rsplit('/',1)[-1] for x in (a,b) if x)
for (a,) in db.execute('SELECT uri FROM attachments'):
    references.add(a.rsplit('/',1)[-1])
files=adb('shell','run-as',pkg,'ls','files/attachments').decode().splitlines()
result={
 'total_saves':db.execute('SELECT COUNT(*) FROM save_items').fetchone()[0],
 'empty_notes':db.execute("SELECT COUNT(*) FROM save_items WHERE content_type='NOTE' AND title='Quick save' AND (notes IS NULL OR trim(notes)='')").fetchone()[0],
 'case_sensitive_import_expected':2,
 'case_sensitive_import_actual':db.execute("SELECT COUNT(*) FROM save_items WHERE url IN ('https://example.com/CaseSensitive','https://example.com/casesensitive')").fetchone()[0],
 'qa_collection_note_saved':db.execute("SELECT COUNT(*) FROM save_items WHERE notes='QA_COLLECTION_NOTE'").fetchone()[0],
 'large_font_note_saved':db.execute("SELECT COUNT(*) FROM save_items WHERE notes='QA_LARGE_FONT_NOTE'").fetchone()[0],
 'attachment_files':files,
 'unreferenced_attachment_files':[x for x in files if x not in references],
}
db.close()
dbpath.unlink()
for suffix in ['-wal','-shm']:
    p=pathlib.Path(str(dbpath)+suffix)
    if p.exists(): p.unlink()
(root/'state-audit.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
