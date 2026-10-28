"""Frozen qualified-only admission verification; no independent final access."""
import argparse,json
from audit_ready_form_research import ROOT,DATA,sha,compare
from summarize_reference_identity import selected
from summarize_autonomous_pose_experiments import reference_key,source_key,summarize


def protocol():
    path=ROOT/'models/viewport-admission-protocol.json';s=json.loads(path.read_bytes())
    if s['compiler']!='qualified-rep-compiler/9' or s['allow_final_test_access'] or s['allow_validation_access'] or s['thresholds_changed']:raise PermissionError('Frozen implementation required')
    for r in s['receipts']:
        if sha(ROOT/r['path'],'sha256_lf' in r)!=r.get('sha256_lf',r.get('sha256')):raise ValueError('Frozen source/input changed '+r['path'])
    return s


def skeletal(scope):
    if scope not in {'development','validation'}:raise PermissionError('Final remains sealed')
    protocol();before=json.loads((DATA/f'uiprmd/preparation-fault-{scope}-replay.json').read_bytes());after=json.loads((DATA/f'uiprmd/viewport-admission-{scope}-replay.json').read_bytes())
    if before['compiler']!='qualified-rep-compiler/8' or after['compiler']!='qualified-rep-compiler/9':raise ValueError('Wrong engine')
    result=compare(before,after,scope);result['promotion_gate_passed']=result.pop('development_promotion_gate_passed') and not result['changed_repetitions']
    result.update(schema='viewport-admission-skeletal-comparison/1',scope=scope,kind='independent-skeletal-regression-not-camera-accuracy',human_rep_precision=None,final_test_access=False,readiness_points_earned=0)
    p=DATA/f'viewport-admission-{scope}-comparison.json'
    if p.exists():raise FileExistsError('Preserve episode comparison')
    p.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n');print(scope,result['after']['counts'],result['promotion_gate_passed'])
    if not result['promotion_gate_passed']:raise SystemExit('Independent regression rejects candidate')


def accepted(row):return {(x['started_ms'],x['completed_ms'],x['verdict']) for x in row.get('attempts',[]) if x['verdict'] in {'CORRECT','ACCEPTABLE'}}


def camera():
    protocol();output=DATA/'viewport-admission-summary'
    if output.exists():raise FileExistsError('Preserve reference registry')
    output.mkdir();inputs={};results={};all_changes=[]
    for cohort in ['capped','complete']:
        before,old=selected('preparation-fault',cohort,inputs);after,new=selected('viewport-admission',cohort,inputs)
        a={source_key(r):r for r in before};b={source_key(r):r for r in after}
        assert len(a)==len(b)==96 and a.keys()==b.keys() and len(new)==32 and old.keys()==new.keys()
        losses=[list(k) for k in old if old[k][0]!='unavailable' and new[k][0]=='unavailable']
        changes=[]
        for k,row in b.items():
            prior=a[k]
            for f in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:assert row[f]==prior[f]
            fields=[f for f in ['status','counts','attempts','faults'] if row.get(f)!=prior.get(f)]
            if fields:changes.append(dict(video=row['video'],side=row['side'],fields=fields,before_counts=prior.get('counts'),after_counts=row.get('counts'),withdrawn_or_shifted=sorted(accepted(prior)-accepted(row)),added_or_shifted=sorted(accepted(row)-accepted(prior))))
        refs={reference_key(r):r for r in after}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],k),selected_model=model,selected_fit=fit,reference_key=refs[k].get('reference_key'),selection_policy='qualified-reference-selection/2',reference_schema='qualified-reference-reps/5',compiler_policy='qualified-rep-compiler/9',release_qualified=False) for k,(model,fit) in sorted(new.items())]
        (output/f'{cohort}-reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n',encoding='utf-8',newline='\n')
        results[cohort]=dict(before=summarize(before),after=summarize(after),reference_losses=losses,backend_choices_equal=old==new,after_usable=sum(v[0]!='unavailable' for v in new.values()),selected_source_changes=changes)
        for model in ['full','heavy']:
            for fit in ['standard','robust']:
                aa=json.loads((DATA/f'preparation-fault-{cohort}-{model}-{fit}/summary.json').read_bytes())['rows'];bb=json.loads((DATA/f'viewport-admission-{cohort}-{model}-{fit}/summary.json').read_bytes())['rows'];assert len(aa)==len(bb)==96
                for x,y in zip(aa,bb):
                    assert source_key(x)==source_key(y)
                    if x.get('attempts')!=y.get('attempts') or x.get('faults')!=y.get('faults') or x.get('status')!=y.get('status'):
                        all_changes.append(dict(cohort=cohort,model=model,fit=fit,video=y['video'],side=y['side'],before_status=x['status'],after_status=y['status'],before_counts=x.get('counts'),after_counts=y.get('counts'),withdrawn_or_shifted=sorted(accepted(x)-accepted(y)),added_or_shifted=sorted(accepted(y)-accepted(x))))
    report=dict(schema='viewport-admission-camera-regression/1',cohorts=results,all_configuration_changes=all_changes,inputs=inputs,no_existing_selected_reference_losses=all(not c['reference_losses'] for c in results.values()),all_qualified_intervals_retained=all(not c['withdrawn_or_shifted'] for c in all_changes),qualified_intervals_added_or_shifted=sum(len(c['added_or_shifted']) for c in all_changes),source_outcome_model_selection=False,human_rep_precision=None,final_test_access=False)
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in {'inputs','all_configuration_changes'}},indent=2));print('Changed configuration/source records:',len(all_changes))
    if not report['no_existing_selected_reference_losses']:raise SystemExit('Reference loss requires explicit correctness review')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['verify','development','validation','camera']);a=p.parse_args()
    if a.stage=='verify':protocol();print('Frozen source/input receipts verified')
    elif a.stage=='camera':camera()
    else:skeletal(a.stage)
