from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
root=Path(__file__).parent/'fixtures'
root.mkdir(exist_ok=True)
image=Image.new('RGB',(1200,800),'white')
draw=ImageDraw.Draw(image)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',64)
draw.text((60,150),'PETTIBOX QA OCR ALBATROSS',font=font,fill='black')
draw.text((60,280),'Receipt total 123.45',font=font,fill='black')
image.save(root/'qa-ocr.png')
image.save(root/'qa-scan.pdf','PDF',resolution=150)
(root/'qa-invalid.pdf').write_bytes(b'This is not a valid PDF')
(root/'qa-empty.txt').write_bytes(b'')
(root/'qa-bookmarks.html').write_text('''<!DOCTYPE NETSCAPE-Bookmark-file-1>
<TITLE>Bookmarks</TITLE><H1>Bookmarks</H1><DL><p>
<DT><H3>QA Import</H3><DL><p>
<DT><A HREF="https://example.com/qa-import-one" TAGS="qa,import">QA Imported One</A>
<DT><A HREF="https://example.com/qa-import-two">QA Imported Two</A>
</DL><p></DL><p>''',encoding='utf-8')
(root/'qa-bookmarks.csv').write_text('title,url,tags,folder\nQA CSV,https://example.com/qa-csv,"qa,csv",QA CSV Folder\n',encoding='utf-8')
(root/'qa-invalid-backup.zip').write_bytes(b'invalid backup')
print('Created',len(list(root.iterdir())),'QA fixtures')
