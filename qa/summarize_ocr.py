"""Summarize Android OCR instrumentation and synthetic extraction evidence."""
import json
import pathlib
import re
import unicodedata

ROOT = pathlib.Path(__file__).parent
tests = []
status = {}
for line in (ROOT / 'ocr-all-languages.log').read_text(encoding='utf-8-sig').splitlines():
    match = re.match(r'INSTRUMENTATION_STATUS: (\w+)=(.*)', line)
    if match:
        status[match[1]] = match[2]
    match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
    if match:
        code = int(match[1])
        if code != 1 and 'test' in status:
            tests.append({'test': status['test'], 'status': code,
                          'failure': status.get('stack', '')})
        status = {}

def normalize(text):
    return ''.join(unicodedata.normalize('NFC', text).split())

def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        nxt = [i]
        for j, y in enumerate(b, 1):
            nxt.append(min(nxt[-1] + 1, row[j] + 1, row[j - 1] + (x != y)))
        row = nxt
    return row[-1]

samples = []
path = ROOT / 'ocr-all-extraction.log'
if path.exists():
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if 'OcrSample' not in line or '{' not in line:
            continue
        sample = json.loads(line[line.index('{'):])
        expected, actual = normalize(sample['expected']), normalize(sample['actual'])
        sample['character_error_rate_without_whitespace'] = round(
            distance(expected, actual) / max(1, len(expected)), 4)
        samples.append(sample)

result = {'tests': tests, 'samples': samples,
          'passed': sum(t['status'] == 0 for t in tests),
          'failed': sum(t['status'] != 0 for t in tests)}
out = ROOT / 'current-evidence' / 'ocr-results.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k not in ('tests', 'samples')}))
for t in tests:
    if t['status'] != 0:
        print(t['test'] + ': ' + t['failure'])
