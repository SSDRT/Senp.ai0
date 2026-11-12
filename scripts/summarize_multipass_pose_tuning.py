"""Compare versioned causal evidence changes on exactly the same development observations."""
import collections
import hashlib
import json
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key, source_key, summarize
from summarize_reference_fit_recovery import choose


def main():
    output=DATA/'multipass-summary'
    output.mkdir(exist_ok=True)
    inputs={}
    results={}
    for cohort in ['capped','complete']:
        selections={}
        registries={}
        for version in ['before','after']:
            groups={fit:{} for fit in ['standard','robust']}
            for model in ['full','heavy']:
                for fit in ['standard','robust']:
                    if version=='after':directory=f'multipass-{cohort}-{model}-{fit}'
                    elif fit=='robust':directory=f'bounded-fit-{cohort}-{model}-replay'
                    else:directory=f'{"calibration-envelope" if cohort=="capped" else "complete-reference"}-{model}-replay'
                    path=DATA/directory/'summary.json'
                    payload=json.loads(path.read_text())
                    groups[fit][model]=payload['rows']
                    inputs[f'{cohort}_{version}_{model}_{fit}']={'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
            choices=choose(groups['standard'],groups['robust'])
            indexes={(model,fit_name):{source_key(r):r for r in groups[fit][model]}
                for model in ['full','heavy'] for fit,fit_name in [('standard','ORDINARY_LEAST_SQUARES'),('robust','BOUNDED_MEDIAN_PAIRWISE')]}
            selected=[r if choices[reference_key(r)][0]=='unavailable' else indexes[choices[reference_key(r)]][source_key(r)]
                for r in groups['standard']['full']]
            assert len(selected)==96 and len({source_key(r) for r in selected})==96
            selections[version]=selected
            registries[version]=choices
        before={source_key(r):r for r in selections['before']}
        after={source_key(r):r for r in selections['after']}
        assert before.keys()==after.keys()
        differences=[]
        for key,row in after.items():
            previous=before[key]
            for field in ['frame_count','evaluated_start_ms','evaluated_end_ms','reference_frame_count','reference_evaluated_end_ms']:
                assert row[field]==previous[field],(key,field)
            changed=[field for field in ['status','counts','attempts','faults'] if row.get(field)!=previous.get(field)]
            if changed:differences.append({'video':row['video'],'exercise':row['exercise'],'side':row['side'],'fields':changed,
                'status_before':previous['status'],'status_after':row['status'],
                'counts_before':previous.get('counts'),'counts_after':row.get('counts')})
        selected_refs={reference_key(r):r for r in selections['after']}
        registry=[dict(zip(['reference_video_sha256','exercise','side','view'],key),selected_model=model,selected_fit=fit,
            reference_key=selected_refs[key].get('reference_key'),selection_policy='qualified-reference-selection/2',
            reference_schema='qualified-reference-reps/5',release_qualified=False)
            for key,(model,fit) in sorted(registries['after'].items())]
        (output/f'{cohort}-reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n')
        results[cohort]={'before':summarize(selections['before']),'after':summarize(selections['after']),
            'selected_models':dict(collections.Counter(m for m,f in registries['after'].values())),
            'selected_fits':dict(collections.Counter(f or 'unavailable' for m,f in registries['after'].values())),
            'reference_choice_changes':[dict(zip(['reference_video_sha256','exercise','side','view'],key),
                before=registries['before'][key],after=value) for key,value in sorted(registries['after'].items()) if value!=registries['before'][key]],
            'outcome_changes':differences,
            'previously_usable_runs_lost':sum(row['status']!='REPLAYED' and before[key]['status']=='REPLAYED' for key,row in after.items())}
    candidate_groups={fit:{} for fit in ['standard','robust']}
    for model in ['full','heavy']:
        for fit in ['standard','robust']:
            path=DATA/f'multipass-candidate-{model}-{fit}'/'summary.json'
            candidate_groups[fit][model]=json.loads(path.read_text())['rows']
            inputs[f'candidate_{model}_{fit}']={'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    candidate_choices=choose(candidate_groups['standard'],candidate_groups['robust'])
    indexed={(model,fit_name):{source_key(r):r for r in candidate_groups[fit][model]}
        for model in ['full','heavy'] for fit,fit_name in [('standard','ORDINARY_LEAST_SQUARES'),('robust','BOUNDED_MEDIAN_PAIRWISE')]}
    candidate_rows=[r if candidate_choices[reference_key(r)][0]=='unavailable' else indexed[candidate_choices[reference_key(r)]][source_key(r)]
        for r in candidate_groups['standard']['full']]
    search_path=DATA/'autonomous-parameter-search.json'
    search=json.loads(search_path.read_text())
    parameter_experiment={'parameters':search['selected_parameters'],'candidates':search['candidate_count'],
        'training_cases':sum(r['generated_training']['cases'] for r in search['candidates']),
        'checking_cases_per_setting':search['baseline_check']['cases'],
        'generated_baseline_check':search['baseline_check'],'generated_candidate_check':search['candidate_check'],
        'real_complete_candidate':summarize(candidate_rows),'promoted':False,
        'reason':'Wider allowances improve the generator score but cannot establish independent human-form precision.'}
    inputs['parameter_search']={'path':str(search_path),'sha256':hashlib.sha256(search_path.read_bytes()).hexdigest()}
    evidence={}
    for variant in ['before','compatible-only','final']:
        path=DATA/f'multipass-evidence-{variant}.json'
        report=json.loads(path.read_text())
        evidence[variant]={'scenarios':report['scenario_count'],'failures':report['failures'],
            'by_scenario':{kind:{'cases':len(rows),'passed':sum(r['passed'] for r in rows),'qualified':sum(r['qualified'] for r in rows)}
                for kind in sorted({r['scenario'] for r in report['rows']})
                for rows in [[r for r in report['rows'] if r['scenario']==kind]]}}
    counterfactual_path=DATA/'multipass-counterfactuals.json'
    counterfactual=json.loads(counterfactual_path.read_text())
    assert counterfactual['scenario_count']==124 and counterfactual['original_accepted_outputs']==31 and counterfactual['failures']==0
    inputs['controlled_feature_corruptions']={'path':str(counterfactual_path),'sha256':hashlib.sha256(counterfactual_path.read_bytes()).hexdigest()}
    report={'kind':'multi-pass-causal-evidence-development-comparison-not-human-form-accuracy',
        'baseline_commit':'d26df1ed3db48f661e0eb9cf0c1cabdcd41ba55f','reference_schema_before':'qualified-reference-reps/4',
        'reference_schema_after':'qualified-reference-reps/5','reference_keys_intentionally_invalidated':True,
        'live_rep_precision':None,'release_qualified':False,'new_manual_labels':0,'source_or_reference_observations_changed':False,
        'cohorts':results,'generated_evidence_controls':evidence,'parameter_experiment':parameter_experiment,
        'controlled_feature_corruptions':{k:counterfactual[k] for k in ['original_accepted_outputs','scenario_count','failures']},'inputs':inputs}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'cohorts':results,'generated_evidence_controls':evidence},indent=2))


if __name__=='__main__':main()
