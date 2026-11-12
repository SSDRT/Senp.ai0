"""Compare the fixed original camera/development inputs and collect scoped JVM results.

Run after the eight provenance-fault replays and scoped JVM suite. Refuses to
overwrite the resulting report; preserve the old report before a new experiment.
No final-test data or raw landmark coordinates are opened or altered here.
"""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
data = root/'test-artifacts/exercise-knowledge'
comparisons=[]
trace_count=0
for cohort in ['capped','complete']:
    for model in ['full','heavy']:
        for fit in ['standard','robust']:
            tag=f'{cohort}-{model}-{fit}'
            old=data/f'evidence-audit-{tag}'
            new=data/f'provenance-fault-{tag}'
            previous=json.loads((old/'summary.json').read_text())
            current=json.loads((new/'summary.json').read_text())
            assert previous == current, tag
            count=0
            for path in new.glob('*.json'):
                if path.name=='summary.json': continue
                a=json.loads((old/path.name).read_text())
                b=json.loads(path.read_text())
                for row in a['trace']: row.pop('evidence',None)
                for row in b['trace']: row.pop('evidence',None)
                assert a==b,(tag,path.name)
                count+=1
            trace_count+=count
            comparisons.append({'configuration':tag,'all_summary_fields_equal':True,
                'trace_count_equal_excluding_passive_evidence':count,
                'current_summary_sha256':hashlib.sha256((new/'summary.json').read_bytes()).hexdigest(),
                'baseline_summary_sha256':hashlib.sha256((old/'summary.json').read_bytes()).hexdigest()})
development = json.loads((data/'uiprmd/provenance-fault-development-replay.json').read_text())
assert development == json.loads((data/'uiprmd/mirror-calibration-development-replay.json').read_text())
counts={}
for module in ['core-motion','android-pose-mediapipe','validation-app']:
    path=root/module/'build/test-results'/('test' if module=='core-motion' else 'testDebugUnitTest')
    values={'tests':0,'failures':0,'errors':0,'skipped':0}
    for xml in path.glob('TEST-*.xml'):
        suite=ET.parse(xml).getroot()
        for k in values: values[k]+=int(suite.get(k,0))
    assert values['tests']>0 and not any(values[k] for k in ['failures','errors','skipped']),(module,values)
    counts[module]=values
result={'kind':'source-provenance-and-mirrored-fault-regression-not-human-accuracy',
    'camera_comparisons':comparisons,'all_equal_trace_count':trace_count,'development_replay_exactly_equal':True,
    'jvm':counts,'actual_live_human_rep_precision':None,'new_human_labels':0,'final_test_opened':False}
output=data/'provenance-fault-verification.json'
assert not output.exists(),'Do not overwrite an immutable verification report'
output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
