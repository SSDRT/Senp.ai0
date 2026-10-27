"""Prospectively frozen clear-start candidate; independent skeleton and camera regressions stay separate."""
import argparse
import json
from audit_ready_form_research import ROOT,DATA,sha,compare
from summarize_reference_identity import selected
from summarize_autonomous_pose_experiments import reference_key,source_key,summarize

PROTOCOL=ROOT/'models/clear-ready-research-protocol.json'


def verify():
    spec=json.loads(PROTOCOL.read_text())
    if (spec.get('schema')!='ready-form-validation/1' or spec.get('kind')!='persistent-clear-start/1' or
        spec.get('compiler')!='qualified-rep-compiler/7' or spec.get('final_test_access') is not False or
        spec.get('subjects')!=['05','06','07'] or spec.get('reference_subject')!='01' or
        spec.get('thresholds_changed') is not False):raise ValueError('Frozen candidate/scope changed')
    for r in spec['source_receipts']:
        if sha(ROOT/r['path'],'sha256_lf' in r)!=r.get('sha256_lf',r.get('sha256')):raise ValueError('Frozen input changed: '+r['path'])
    return spec


def require_development():
    if not json.loads((DATA/'clear-ready-development-comparison.json').read_text())['promotion_gate_passed']:
        raise ValueError('Failed development candidate cannot progress')


def skeletal(scope):
    if scope=='validation':require_development()
    baseline=DATA/'uiprmd'/('provenance-fault-development-replay.json' if scope=='development' else 'side-binding-validation-fixed-replay.json')
    candidate=DATA/f'uiprmd/clear-ready-{scope}-replay.json'
    before,after=[json.loads(p.read_text()) for p in [baseline,candidate]]
    if before['compiler']!='qualified-rep-compiler/5' or after['compiler']!='qualified-rep-compiler/7':raise ValueError('Wrong candidate/baseline')
    result=compare(before,after,scope)
    result['promotion_gate_passed']=result.pop('development_promotion_gate_passed')
    result.update(schema='persistent-clear-start-skeletal/1',scope=scope,measurement_domain='skeletal',
        kind='already-opened-skeletal-selection-data-not-camera-or-final-test-accuracy',protocol_sha256=sha(PROTOCOL),
        baseline_sha256=sha(baseline),candidate_sha256=sha(candidate),final_test_access=False,human_live_rep_precision=None)
    output=DATA/f'clear-ready-{scope}-comparison.json'
    if output.exists():raise FileExistsError('Preserve comparison')
    output.write_text(json.dumps(result,indent=2)+'\n',newline='\n')
    print(json.dumps({k:v for k,v in result.items() if k!='changed_repetitions'},indent=2))


def camera():
    require_development();directory=DATA/'clear-ready-summary'
    if directory.exists():raise FileExistsError('Preserve camera selection')
    directory.mkdir();results={};inputs={}
    for cohort in ['capped','complete']:
        before,old=selected('provenance-fault',cohort,inputs);after,new=selected('clear-ready',cohort,inputs)
        a={source_key(r):r for r in before};b={source_key(r):r for r in after}
        if len(a)!=96 or a.keys()!=b.keys() or len(new)!=32 or old.keys()!=new.keys():raise ValueError('Fixed cohorts changed')
        changes=[]
        for key,row in b.items():
            prior=a[key]
            for field in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:
                if row[field]!=prior[field]:raise ValueError('Input timing/extent changed')
            fields=[f for f in ['status','counts','attempts','faults'] if row.get(f)!=prior.get(f)]
            if fields:changes.append(dict(video=row['video'],side=row['side'],fields=fields,before_counts=prior.get('counts'),after_counts=row.get('counts')))
        losses=[list(k) for k in old if old[k][0]!='unavailable' and new[k][0]=='unavailable']
        refs={reference_key(r):r for r in after}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],k),selected_model=model,selected_fit=fit,
            reference_key=refs[k].get('reference_key'),selection_policy='qualified-reference-selection/2',
            reference_schema='qualified-reference-reps/5',compiler_policy='qualified-rep-compiler/7',release_qualified=False)
            for k,(model,fit) in sorted(new.items())]
        (directory/f'{cohort}-reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n',newline='\n')
        results[cohort]=dict(before=summarize(before),after=summarize(after),reference_losses=losses,
            before_usable=sum(v[0]!='unavailable' for v in old.values()),after_usable=sum(v[0]!='unavailable' for v in new.values()),
            backend_choices_equal=old==new,changed_source_outcomes=changes)
    report=dict(schema='persistent-clear-start-camera-regression/1',protocol_sha256=sha(PROTOCOL),cohorts=results,inputs=inputs,
        promotion_gate_passed=all(not r['reference_losses'] for r in results.values()),human_rep_precision=None)
    (directory/'summary.json').write_text(json.dumps(report,indent=2)+'\n',newline='\n')
    print(json.dumps({k:{f:r[f] for f in ['before_usable','after_usable','backend_choices_equal','reference_losses']} for k,r in results.items()},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('scope',choices=['verify','development','validation','camera'])
    args=parser.parse_args();verify()
    if args.scope=='camera':camera()
    elif args.scope!='verify':skeletal(args.scope)
    else:print('Frozen source and input receipts verified')
