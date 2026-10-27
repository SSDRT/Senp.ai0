"""Reference-only backend comparison; source outcomes are reported, never selectors."""
import json
from audit_ready_form_research import ROOT,DATA,sha
from summarize_reference_identity import selected
from summarize_autonomous_pose_experiments import reference_key,source_key,summarize


def main():
    for scope in ['development','validation']:
        if not json.loads((DATA/f'preparation-fault-{scope}-comparison.json').read_bytes())['promotion_gate_passed']:
            raise PermissionError('Skeletal selection gate failed')
    directory=DATA/'preparation-fault-summary'
    if directory.exists():raise FileExistsError('Preserve reference choices')
    directory.mkdir();results={};inputs={}
    for cohort in ['capped','complete']:
        before,old=selected('clear-ready',cohort,inputs);after,new=selected('preparation-fault',cohort,inputs)
        a={source_key(r):r for r in before};b={source_key(r):r for r in after}
        if len(a)!=96 or a.keys()!=b.keys() or len(new)!=32 or old.keys()!=new.keys():raise ValueError('Fixed cohort changed')
        changes=[]
        for key,row in b.items():
            prior=a[key]
            for f in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:
                if row[f]!=prior[f]:raise ValueError('Original extent/timebase changed')
            fields=[f for f in ['status','counts','attempts','faults'] if row.get(f)!=prior.get(f)]
            if fields:changes.append(dict(video=row['video'],side=row['side'],fields=fields,before_counts=prior.get('counts'),after_counts=row.get('counts')))
        losses=[list(k) for k in old if old[k][0]!='unavailable' and new[k][0]=='unavailable']
        refs={reference_key(r):r for r in after}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],k),selected_model=model,selected_fit=fit,
            reference_key=refs[k].get('reference_key'),selection_policy='qualified-reference-selection/2',
            reference_schema='qualified-reference-reps/5',compiler_policy='qualified-rep-compiler/8',release_qualified=False)
            for k,(model,fit) in sorted(new.items())]
        (directory/f'{cohort}-reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n',encoding='utf-8',newline='\n')
        results[cohort]=dict(before=summarize(before),after=summarize(after),reference_losses=losses,
            before_usable=sum(v[0]!='unavailable' for v in old.values()),after_usable=sum(v[0]!='unavailable' for v in new.values()),
            backend_choices_equal=old==new,changed_source_outcomes=changes)
    report=dict(schema='preparation-fault-camera-regression/1',cohorts=results,inputs=inputs,
        promotion_gate_passed=all(not r['reference_losses'] for r in results.values()),source_outcome_model_selection=False,
        human_rep_precision=None,final_test_access=False)
    (directory/'summary.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(results,indent=2))
    if not report['promotion_gate_passed']:raise SystemExit('Reference loss rejects candidate')


if __name__=='__main__':main()
