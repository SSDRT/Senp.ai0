"""Frozen candidate comparison on development 01–04 only; never reads final subjects."""
import argparse
import hashlib
import json
from pathlib import Path

from evaluate_uiprmd_development import evaluate_rows, QUALIFIED
from extract_exercise_quality_features import ROOT, DATA

PROTOCOL=ROOT/'models/ready-form-research-protocol.json'


def sha(path,normalize=False):
    raw=path.read_bytes()
    return hashlib.sha256(raw.replace(b'\r\n',b'\n') if normalize else raw).hexdigest()


def verify_protocol():
    spec=json.loads(PROTOCOL.read_text())
    if (spec.get('schema')!='compatible-ready-form-research/1' or spec.get('final_test_access') is not False or
        spec.get('validation_access') is not False or spec.get('development_subjects')!=['01','02','03','04'] or
        spec.get('development_reps')!=160 or spec.get('candidate_compiler')!='qualified-rep-compiler/6'):
        raise ValueError('Frozen development-only protocol changed')
    for receipt in spec['source_receipts']:
        normalized='sha256_lf' in receipt
        if sha(ROOT/receipt['path'],normalized)!=receipt.get('sha256_lf',receipt.get('sha256')):
            raise ValueError('Frozen input/source changed: '+receipt['path'])
    return spec


def compare(before,after,scope='development'):
    if scope not in {'development','validation'}:raise PermissionError('Final-test comparison is forbidden')
    subjects=['01','02','03','04'] if scope=='development' else ['05','06','07']
    total=160 if scope=='development' else 120
    for report in [before,after]:
        keys=[(r['subject'],r['movement'],r['correct']) for r in report['rows']]
        expected={(s,m,c) for s in subjects for m in ['m01','m07'] for c in [True,False]}
        if len(keys)!=len(expected) or set(keys)!=expected:raise ValueError('Incomplete/duplicate/out-of-scope cohort')
        for row in report['rows']:
            if (len(row['episodes'])!=10 or any(e['correct'] is not row['correct'] for e in row['episodes']) or
                row['reference_subject']!=('02' if row['subject']=='01' else '01')):
                raise ValueError('Original repetition labels/reference assignment changed')
    old={tuple(r[k] for k in ['subject','movement','correct']):r for r in before['rows']}
    for row in after['rows']:
        prior=old[tuple(row[k] for k in ['subject','movement','correct'])]
        for key in ['episodes','source_sha256','reference_sha256','reference_subject','source_member','exercise_id','side']:
            if row[key]!=prior[key]:raise ValueError('Original labels/source/reference changed: '+key)
    metrics=[evaluate_rows(r['rows']) for r in [before,after]]
    def indexed(result):return {(r['subject'],r['movement'],r['correct'],r['episode']):r for r in result['rep_results']}
    a,b=map(indexed,metrics)
    if len(a)!=total or a.keys()!=b.keys():raise ValueError('Frozen label denominator changed')
    changes=[dict(subject=k[0],movement=k[1],correct=k[2],episode=k[3],
                  before=a[k]['verdict'],after=b[k]['verdict']) for k in a if a[k]['verdict']!=b[k]['verdict']]
    lost=[r for r in changes if r['correct'] and r['before'] in QUALIFIED and r['after'] not in QUALIFIED]
    added_bad=[r for r in changes if not r['correct'] and r['before'] not in QUALIFIED and r['after'] in QUALIFIED]
    return dict(before={k:v for k,v in metrics[0].items() if k!='rep_results'},
        after={k:v for k,v in metrics[1].items() if k!='rep_results'},changed_repetitions=changes,
        previously_counted_correct_lost=lost,new_incorrect_counts=added_bad,
        development_promotion_gate_passed=not lost and not added_bad)


def main(verify_only):
    verify_protocol()
    if verify_only:
        print('Frozen source/input receipts verified; only development replay is authorized');return
    before_path=DATA/'uiprmd/provenance-fault-development-replay.json'
    after_path=DATA/'uiprmd/ready-form-development-replay.json'
    before,after=[json.loads(p.read_text()) for p in [before_path,after_path]]
    if after['compiler']!='qualified-rep-compiler/6':raise ValueError('Wrong candidate compiler')
    result=compare(before,after)
    result.update(schema='compatible-ready-form-development/1',kind='independent-skeletal-development-not-camera-accuracy',
        protocol_sha256=sha(PROTOCOL),before_replay_sha256=sha(before_path),after_replay_sha256=sha(after_path),
        final_test_access=False,validation_access=False,readiness_points_earned=0,live_human_rep_precision=None)
    output=DATA/'ready-form-development-comparison.json'
    if output.exists():raise FileExistsError('Preserve candidate evidence')
    output.write_text(json.dumps(result,indent=2)+'\n',newline='\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--verify-only',action='store_true')
    main(parser.parse_args().verify_only)
