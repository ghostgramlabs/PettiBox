import subprocess,time,pathlib,json
root=pathlib.Path(__file__).parent/'evidence'
serial=(root.parent/'device.txt').read_text().strip()
package='com.ghostgramlabs.pettibox'
def adb(*args):
    r=subprocess.run(['adb','-s',serial,*args],capture_output=True,text=True)
    if r.returncode: raise RuntimeError(r.stderr)
    return r.stdout
adb('shell','dumpsys','gfxinfo',package,'reset')
start=time.time()
for i in range(5):
    for x in [430,665,910,180]:
        adb('shell','input','tap',str(x),'2245')
        time.sleep(.5)
    adb('shell','input','swipe','550','1880','550','700','350')
    adb('shell','input','swipe','550','700','550','1880','350')
(root/'api35-navigation-workload.json').write_text(json.dumps({'navigation_cycles':5,'tab_taps':20,'swipes':10,'duration_seconds':time.time()-start},indent=2))
(root/'api35-gfx-navigation.txt').write_text(adb('shell','dumpsys','gfxinfo',package))
(root/'api35-memory-after-navigation.txt').write_text(adb('shell','dumpsys','meminfo',package))
(root/'api35-logcat.txt').write_text(adb('shell','logcat','-d','-t','5000'))
print('Collected API35 navigation frame statistics, memory, and logcat',flush=True)
