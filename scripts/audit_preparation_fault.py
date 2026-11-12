"""Frozen development comparison for the armed-preparation guard; no final access."""
import hashlib
import json
from pathlib import Path
from evaluate_uiprmd_development import evaluate_rows,QUALIFIED

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge'
PROTOCOL=ROOT/'models/preparation-fault-protocol.json'


def frozen_protocol():
    spec=json.loads(PROTOCOL.read_bytes())
    if (spec['schema']!='preparation-fault-research/1' or spec['compiler']!='qualified-rep-compiler/8' or
        spec['allow_validation_access'] or spec['allow_final_test_access'] or spec['thresholds_changed']):
        raise PermissionError('Frozen development-only candidate required')
    for r in spec['receipts']:
        raw=(ROOT/r['path']).read_bytes()
        if 'sha256_lf' in r:raw=raw.replace(b'\r\n',b'\n')
        if hashlib.sha256(raw).hexdigest()!=r.get('sha256_lf',r.get('sha256')):raise ValueError('Frozen source/input changed: '+r['path'])
    return spec


def main():
    spec=frozen_protocol();output=DATA/'preparation-fault-development-comparison.json'
    if output.exists():raise FileExistsError('Preserve measured comparison')
    old=json.loads((ROOT/spec['baseline_development']).read_bytes())
    new_path=DATA/'uiprmd/preparation-fault-development-replay.json';new=json.loads(new_path.read_bytes())
    if old['compiler']!='qualified-rep-compiler/7' or new['compiler']!=spec['compiler'] or old['manifest_sha256']!=new['manifest_sha256']:
        raise ValueError('Wrong engine or original source manifest')
    expected={(s,m,c) for s in ['01','02','03','04'] for m in ['m01','m07'] for c in [True,False]}
    for replay in [old,new]:
        keys=[(r['subject'],r['movement'],r['correct']) for r in replay['rows']]
        if len(keys)!=16 or set(keys)!=expected:raise PermissionError('Only complete original development scope permitted')
    for a,b in zip(old['rows'],new['rows']):
        for key in ['subject','movement','correct','side','source_sha256','episodes','reference_subject','reference_sha256']:
            if a[key]!=b[key]:raise ValueError('Original source/target/labels changed')
    before=evaluate_rows(old['rows']);after=evaluate_rows(new['rows'])
    if before['counts']['total_reps']!=160 or after['counts']['total_reps']!=160:raise ValueError('Label denominator changed')
    lost=[];added=[];changes=[]
    for a,b in zip(before['rep_results'],after['rep_results']):
        if any(a[k]!=b[k] for k in ['subject','movement','correct','episode']):raise ValueError('Episode identity changed')
        change=dict(subject=a['subject'],movement=a['movement'],correct=a['correct'],episode=a['episode'],before=a['verdict'],after=b['verdict'])
        if a['verdict']!=b['verdict']:changes.append(change)
        if a['correct'] and a['verdict'] in QUALIFIED and b['verdict'] not in QUALIFIED:lost.append(change)
        if not a['correct'] and a['verdict'] not in QUALIFIED and b['verdict'] in QUALIFIED:added.append(change)
    result=dict(schema='preparation-fault-development-comparison/1',measurement_domain='skeletal',
        before={k:v for k,v in before.items() if k!='rep_results'},after={k:v for k,v in after.items() if k!='rep_results'},
        previously_counted_correct_lost=lost,new_incorrect_counts=added,changed_episodes=changes,
        promotion_gate_passed=not lost and not added,protocol_sha256=hashlib.sha256(PROTOCOL.read_bytes()).hexdigest(),
        candidate_replay_sha256=hashlib.sha256(new_path.read_bytes()).hexdigest(),validation_access=False,final_test_access=False,
        human_live_rep_precision=None,readiness_points_earned=0)
    output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(result,indent=2))
    if not result['promotion_gate_passed']:raise SystemExit('Candidate rejected before validation')


if __name__=='__main__':main()
