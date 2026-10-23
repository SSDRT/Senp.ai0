"""Reference-only four-stage selection; keep every established model/fit before recovery."""
import collections
import hashlib
import json
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key,source_key,summarize


def choose(standard,robust):
    groups={}
    for model in ['full','heavy']:
        for fit,rows in [('standard',standard[model]),('robust',robust[model])]:
            grouped=collections.defaultdict(list)
            for row in rows:grouped[reference_key(row)].append(row)
            for key,values in grouped.items():
                assert len({v['status'] for v in values})==1
                groups[model,fit,key]=values[0]
    keys={reference_key(r) for r in standard['full']}
    choices={}
    for key in keys:
        selection=('unavailable',None)
        for model in ['full','heavy']:
            if groups[model,'standard',key]['status']=='REPLAYED':selection=(model,'ORDINARY_LEAST_SQUARES');break
        if selection[0]=='unavailable':
            for model in ['full','heavy']:
                if (groups[model,'standard',key]['status'] in ['WEAK_REFERENCE','LOW_SELF_RECONSTRUCTION'] and
                    groups[model,'robust',key]['status']=='REPLAYED'):
                    selection=(model,'BOUNDED_MEDIAN_PAIRWISE');break
        choices[key]=selection
    return choices


def main():
    output=DATA/'reference-fit-recovery-summary';output.mkdir(exist_ok=True)
    files={}
    reports={}
    for cohort,standard_prefix,robust_prefix in [('capped','calibration-envelope','bounded-fit-capped'),
        ('complete','complete-reference','bounded-fit-complete')]:
        standard={};robust={}
        for model in ['full','heavy']:
            for label,prefix,target in [('standard',standard_prefix,standard),('robust',robust_prefix,robust)]:
                path=DATA/(prefix+'-'+model+'-replay')/'summary.json'
                target[model]=json.loads(path.read_text())['rows'];files[cohort+'_'+model+'_'+label]=path
        cohort_keys={source_key(r) for r in standard['full']}
        assert len(cohort_keys)==96
        for values in list(standard.values())+list(robust.values()):
            assert len(values)==96 and {source_key(r) for r in values}==cohort_keys
        choices=choose(standard,robust)
        before=choose(standard,{m:[dict(r,status='NOT_TRIED') for r in values] for m,values in robust.items()})
        indexed={(model,fit):{source_key(r):r for r in values} for model,items in [('full',(standard['full'],robust['full'])),
            ('heavy',(standard['heavy'],robust['heavy']))] for fit,values in zip(['ORDINARY_LEAST_SQUARES','BOUNDED_MEDIAN_PAIRWISE'],items)}
        def selected(registry):
            return [r if registry[reference_key(r)][0]=='unavailable' else indexed[registry[reference_key(r)]][source_key(r)] for r in standard['full']]
        old=selected(before);new=selected(choices)
        old_by_key={source_key(r):r for r in old};differences=[]
        for row in new:
            previous=old_by_key[source_key(row)]
            assert row['frame_count']==previous['frame_count'] and row['evaluated_end_ms']==previous['evaluated_end_ms']
            if previous['status']=='REPLAYED':
                for field in ['status','reference_key','counts','attempts','faults']:
                    if row.get(field)!=previous.get(field):differences.append({'video':row['video'],'side':row['side'],'field':field})
        selected_reference_keys={reference_key(r):r.get('reference_key') for r in new}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],key),selected_model=value[0],selected_fit=value[1],
                       reference_key=selected_reference_keys[key],
                       selection_policy='qualified-reference-selection/2',release_qualified=False) for key,value in sorted(choices.items())]
        (output/(cohort+'-reference-backend-selection.json')).write_text(json.dumps(registry,indent=2)+'\n')
        reports[cohort]={'before':summarize(old),'after':summarize(new),'previously_usable_outcome_differences':differences,
            'selected_models':dict(collections.Counter(model for model,fit in choices.values())),
            'selected_fits':dict(collections.Counter(fit or 'unavailable' for model,fit in choices.values())),
            'changes':[dict(zip(['reference_video_sha256','exercise','side','view'],key),before=before[key],after=value) for key,value in choices.items() if value!=before[key]]}
    result={'kind':'reference-only-fit-recovery-not-human-rep-accuracy','baseline_commit':'7a052f5d3d07303fd06b02e1aaf5f02df6952c46',
        'selection_policy':'qualified-reference-selection/2','reference_policy_schema':'qualified-reference-reps/4',
        'new_manual_labels':0,'live_rep_precision':None,'release_qualified':False,'cohorts':reports,
        'inputs':{name:{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in files.items()},
        'limits':['Same development people/recordings; no new independent rep truth.',
            'The robust fit is not uniformly better. It is tried only after both established model fits fail eligible curve checks.',
            'Source outcomes and clip labels never choose a model or fit.']}
    (output/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(reports,indent=2))


if __name__=='__main__':main()
