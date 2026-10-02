import subprocess, time, pathlib, json
root=pathlib.Path(__file__).parent/'evidence'
package='com.ghostgramlabs.pettibox'
def adb(*args):
    serial=(root.parent/'device.txt').read_text().strip() if (root.parent/'device.txt').exists() else 'emulator-5554'
    result=subprocess.run(['adb','-s',serial,*args],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout
results=[]
for i in range(5):
    adb('shell','am','force-stop',package)
    result=adb('shell','am','start','-W','-n',package+'/.MainActivity')
    results.append(result)
    time.sleep(1)
(root/'startup-repeated.txt').write_text('\n\n'.join(results))
print('\n'.join(results),flush=True)
adb('shell','dumpsys','gfxinfo',package,'reset')
start=time.time()
for i in range(5):
    for x in [430,665,910,180]:
        adb('shell','input','tap',str(x),'2175')
        time.sleep(.3)
    adb('shell','input','swipe','550','1880','550','700','350')
    adb('shell','input','swipe','550','700','550','1880','350')
(root/'performance-workload.json').write_text(json.dumps({'navigation_cycles':5,'tab_taps':20,'swipes':10,'duration_seconds':time.time()-start},indent=2))
(root/'gfx-navigation.txt').write_text(adb('shell','dumpsys','gfxinfo',package))
(root/'memory-after-navigation.txt').write_text(adb('shell','dumpsys','meminfo',package))
print('Navigation workload completed',flush=True)
