import subprocess, sys, re, xml.etree.ElementTree as ET, pathlib, time
sys.stdout.reconfigure(encoding='utf-8')
ROOT=pathlib.Path(__file__).parent/'evidence'
def adb(*args):
    serial=(ROOT.parent/'device.txt').read_text().strip() if (ROOT.parent/'device.txt').exists() else 'emulator-5554'
    result=subprocess.run(['adb','-s',serial,*args],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout
def dump(name='screen'):
    adb('shell','uiautomator','dump','/sdcard/qa.xml')
    adb('pull','/sdcard/qa.xml',str(ROOT/(name+'.xml')))
    return ET.parse(ROOT/(name+'.xml')).getroot()
def show(root):
    for n in root.iter('node'):
        if n.get('text') or n.get('content-desc'):
            print(n.get('text'), '|', n.get('content-desc'), '|',n.get('bounds'))
if sys.argv[1]=='dump': show(dump(sys.argv[2] if len(sys.argv)>2 else 'screen'))
elif sys.argv[1]=='tap':
    root=dump()
    matches=[n for n in root.iter('node') if n.get('content-desc')==sys.argv[2]]
    if not matches: matches=[n for n in root.iter('node') if n.get('text')==sys.argv[2]]
    if not matches: raise SystemExit('Not found: '+sys.argv[2])
    n=matches[int(sys.argv[3]) if len(sys.argv)>3 else 0]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    print(adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)))
    show(dump())
elif sys.argv[1]=='shot':
    adb('shell','screencap','-p','/sdcard/qa.png')
    adb('pull','/sdcard/qa.png',str(ROOT/(sys.argv[2]+'.png')))
