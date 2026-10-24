"""Reference identity guard comparison against the fixed preceding engine outputs."""
import hashlib
import json
import argparse
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key, source_key, summarize
from summarize_reference_fit_recovery import choose


def selected(prefix,cohort,inputs):
    groups={fit:{} for fit in ['standard','robust']}
    for model in ['full','heavy']:
        for fit in groups:
            path=DATA/f'{prefix}-{cohort}-{model}-{fit}'/'summary.json'
            groups[fit][model]=json.loads(path.read_text())['rows']
            inputs[f'{prefix}_{cohort}_{model}_{fit}']={'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    choices=choose(groups['standard'],groups['robust'])
    indexed={(model,fitname):{source_key(r):r for r in groups[fit][model]}
        for model in ['full','heavy'] for fit,fitname in [('standard','ORDINARY_LEAST_SQUARES'),('robust','BOUNDED_MEDIAN_PAIRWISE')]}
    rows=[r if choices[reference_key(r)][0]=='unavailable' else indexed[choices[reference_key(r)]][source_key(r)]
        for r in groups['standard']['full']]
    return rows,choices


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix',default='readiness-identity')
    parser.add_argument('--baseline-prefix',default='multipass')
    parser.add_argument('--compiler',default='qualified-rep-compiler/2')
    parser.add_argument('--baseline-commit',default='4d899899628e08c0db77fddcdad022cb7f081edf')
    args=parser.parse_args()
    if not all(p and all(c.isalnum() or c=='-' for c in p) for p in [args.prefix,args.baseline_prefix]):
        raise ValueError('Invalid managed report prefix')
    output=DATA/(args.prefix+'-summary');output.mkdir(exist_ok=True)
    results={};inputs={}
    for cohort in ['capped','complete']:
        before,old_choices=selected(args.baseline_prefix,cohort,inputs)
        after,choices=selected(args.prefix,cohort,inputs)
        old={source_key(r):r for r in before};new={source_key(r):r for r in after}
        assert len(old)==len(new)==96 and old.keys()==new.keys()
        differences=[];changed_keys=0
        for key,row in new.items():
            previous=old[key]
            for field in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:
                assert row[field]==previous[field]
            for field in ['status','counts','attempts','faults']:
                if row.get(field)!=previous.get(field):differences.append({'video':row['video'],'side':row['side'],'field':field})
            if previous['status']=='REPLAYED':changed_keys+=row.get('reference_key')!=previous.get('reference_key')
        assert not differences and old_choices==choices
        refs={reference_key(r):r for r in after}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],key),selected_model=model,selected_fit=fit,
            reference_key=refs[key].get('reference_key'),selection_policy='qualified-reference-selection/2',
            reference_schema='qualified-reference-reps/5',compiler_policy=args.compiler,release_qualified=False)
            for key,(model,fit) in sorted(choices.items())]
        (output/f'{cohort}-reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n')
        results[cohort]={'before':summarize(before),'after':summarize(after),'outcome_differences':differences,
            'reference_choices_unchanged':True,'ready_run_keys_invalidated':changed_keys}
    report={'kind':'reference-compiler-regression-comparison-not-human-accuracy','baseline_commit':args.baseline_commit,
        'compiler_policy':args.compiler,'reference_schema':'qualified-reference-reps/5','live_rep_precision':None,
        'release_qualified':False,'cohorts':results,'inputs':inputs}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(results,indent=2))


if __name__=='__main__':main()
