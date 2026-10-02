"""Reproduce the DAO's candidate cap followed by SearchViewModel filtering.

This is a source-level SQL reproduction, not an emulator UI test.
"""
import sqlite3, json
from pathlib import Path
db=sqlite3.connect(':memory:')
db.execute('CREATE TABLE save_items(id INTEGER, created_at INTEGER, is_pending_delete INTEGER, contentType TEXT, title TEXT)')
db.execute("INSERT INTO save_items VALUES(1,1,0,'IMAGE','Older image')")
db.executemany("INSERT INTO save_items VALUES(?,?,0,'LINK',?)",[(i,i,'Recent link') for i in range(2,207)])
candidates=db.execute('SELECT * FROM save_items WHERE is_pending_delete = 0 ORDER BY created_at DESC LIMIT 200').fetchall()
result={'library_items':206,'expected_image_filter_matches':1,'actual_matches_after_candidate_cap':len([r for r in candidates if r[3]=='IMAGE']),'oldest_in_candidates':min(r[1] for r in candidates),'expected_oldest_created_at':1}
Path(__file__).with_name('evidence').joinpath('search-limit-reproduction.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
