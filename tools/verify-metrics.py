"""Fail CI if exported measurements or their raw evidence are incomplete."""
import json,pathlib,statistics
root=pathlib.Path(__file__).resolve().parents[1]
metrics=json.loads((root/'docs/m0-metrics.json').read_text())
assert metrics['backgroundSeconds']>=300 and metrics['sessionPreserved'] and metrics['backgroundContinuation']
compression=metrics['compression'];assert compression['sameTar'] and compression['gzipUnpackMs']>0 and compression['xzUnpackMs']>0
startup=metrics['startup'];samples=startup['samples'];assert len(samples)>=5 and startup['iterations']==len(samples)
for sample in samples:
    assert 0<sample['iconToReadyMs']<=sample['iconToFirstTokenMs']
    for phase in ['idle','streaming']:
        processes=sample[phase];assert {'java-app','proot','node-native'}<={p['kind'] for p in processes}
        for process in processes:
            assert process['pssKb']>0 and process['javaHeapPssKb']>=0 and process['nativeHeapPssKb']>=0
            path=(root/'docs'/process['dump']).resolve();assert path.is_relative_to((root/'docs/m0-memory').resolve()) and path.is_file()
assert startup['iconToReadyMedianMs']==statistics.median(s['iconToReadyMs'] for s in samples)
assert startup['iconToFirstTokenMedianMs']==statistics.median(s['iconToFirstTokenMs'] for s in samples)
sizes=json.loads((root/'docs/m0-apk-sizes.json').read_text());assert {s['abi'] for s in sizes}=={'arm64-v8a','x86_64'}
assert next(s for s in sizes if s['abi']=='arm64-v8a')['bytes']<=150000000
print(json.dumps({'samples':len(samples),'backgroundSeconds':metrics['backgroundSeconds'],'rawMemoryDumps':sum(len(s[p]) for s in samples for p in ['idle','streaming']),'verified':True}))
