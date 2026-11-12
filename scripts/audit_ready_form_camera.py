"""Compare reference-only backend choices without interpreting clip labels as rep truth."""
import json
from extract_exercise_quality_features import DATA
from summarize_reference_identity import selected
from summarize_autonomous_pose_experiments import reference_key, source_key, summarize


def main():
    output=DATA/'ready-form-camera-comparison.json'
    if output.exists():raise FileExistsError('Preserve camera comparison')
    results={};inputs={}
    for cohort in ['capped','complete']:
        before,old=selected('provenance-fault',cohort,inputs)
        after,new=selected('ready-form',cohort,inputs)
        a={source_key(r):r for r in before};b={source_key(r):r for r in after}
        if len(a)!=96 or a.keys()!=b.keys() or len(old)!=32 or old.keys()!=new.keys():raise ValueError('Fixed cohorts changed')
        changes=[]
        for key,row in b.items():
            prior=a[key]
            for field in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:
                if row[field]!=prior[field]:raise ValueError('Original input timing/extent changed')
            fields=[f for f in ['status','counts','attempts','faults'] if row.get(f)!=prior.get(f)]
            if fields:changes.append(dict(video=row['video'],side=row['side'],fields=fields,before_counts=prior.get('counts'),after_counts=row.get('counts')))
        losses=[list(key) for key in old if old[key][0]!='unavailable' and new[key][0]=='unavailable']
        gains=[list(key) for key in old if old[key][0]=='unavailable' and new[key][0]!='unavailable']
        results[cohort]=dict(before=summarize(before),after=summarize(after),reference_losses=losses,reference_gains=gains,
            before_usable=sum(v[0]!='unavailable' for v in old.values()),after_usable=sum(v[0]!='unavailable' for v in new.values()),
            changed_source_outcomes=changes)
    result=dict(schema='ready-form-camera-comparison/1',kind='fixed-camera-input-regression-not-human-precision',
        cohorts=results,inputs=inputs,promotion_gate_no_reference_loss=all(not r['reference_losses'] for r in results.values()),
        selection='unchanged-reference-only-backend-priority',human_rep_precision=None)
    output.write_text(json.dumps(result,indent=2)+'\n',newline='\n')
    print(json.dumps({k:{f:r[f] for f in ['before_usable','after_usable','reference_losses','reference_gains']} for k,r in results.items()},indent=2))


if __name__=='__main__':main()
