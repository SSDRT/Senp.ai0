"""Separate algorithm recovery on frozen references from additional full-reference footage."""
import collections
import hashlib
import json
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key,source_key,select_reference_models,summarize


def main():
    output=DATA/'calibration-recovery-summary';output.mkdir(exist_ok=True)
    directories={'before_full':'departure-peak-full-replay','before_heavy':'departure-peak-heavy-replay',
        'median_full':'median-fit-full-replay','median_heavy':'median-fit-heavy-replay',
        'envelope_full':'calibration-envelope-full-replay','envelope_heavy':'calibration-envelope-heavy-replay',
        'complete_standard_full':'complete-reference-standard-full-replay','complete_standard_heavy':'complete-reference-standard-heavy-replay',
        'complete_envelope_full':'complete-reference-full-replay','complete_envelope_heavy':'complete-reference-heavy-replay'}
    inputs={name:DATA/directory/'summary.json' for name,directory in directories.items()}
    rows={name:json.loads(path.read_text())['rows'] for name,path in inputs.items()}
    frozen={source_key(r):r for r in rows['before_full']}
    for name,values in rows.items():
        assert len(values)==96 and {source_key(r) for r in values}==set(frozen)
        for row in values:
            old=frozen[source_key(row)]
            for key in ['video_sha256','reference_video_sha256','frame_count','evaluated_start_ms','evaluated_end_ms']:
                assert row[key]==old[key],(name,source_key(row),key)
    selected={};choices={}
    for variant in ['before','median','envelope','complete_standard','complete_envelope']:
        full,heavy=rows[variant+'_full'],rows[variant+'_heavy']
        choices[variant]=select_reference_models(full,heavy);heavy_by_key={source_key(r):r for r in heavy}
        selected[variant]=[heavy_by_key[source_key(r)] if choices[variant][reference_key(r)]=='heavy' else r for r in full]
    old_by_key={source_key(r):r for r in selected['before']}
    shared=[r for r in selected['envelope'] if old_by_key[source_key(r)]['status']=='REPLAYED']
    differences=[]
    for row in shared:
        for key in ['status','counts','attempts','faults']:
            if row.get(key)!=old_by_key[source_key(row)].get(key):differences.append({'video':row['video'],'side':row['side'],'field':key})
    registry=[dict(zip(['reference_video_sha256','exercise','side','view'],key),selected_model=value,
                   selection_basis='reference-calibration-only-full-first',release_qualified=False) for key,value in sorted(choices['envelope'].items())]
    (output/'reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n')
    changed=[]
    for variant in ['envelope','complete_standard','complete_envelope']:
        for key,value in choices[variant].items():
            old=choices['before'][key]
            if value!=old:changed.append(dict(zip(['reference_video_sha256','exercise','side','view'],key),variant=variant,before=old,after=value))
    extractions={model:json.loads((DATA/('complete-reference-'+model+'-extraction.json')).read_text()) for model in ['full','heavy']}
    for model,e in extractions.items():assert len(e['videos'])==16 and not e['failures']
    report={'kind':'calibration-availability-and-reference-evidence-not-independent-live-accuracy','baseline_commit':'053ea72714d5b2e161b7c0605ea2daca3211a250',
        'live_rep_precision':None,'release_qualified':False,'new_human_labels':0,'reference_policy_schema':'qualified-reference-reps/4',
        'per_model':{name:summarize(values) for name,values in rows.items()},
        'selected':{name:summarize(values) for name,values in selected.items()},
        'selected_model_counts':{name:dict(collections.Counter(value.values())) for name,value in choices.items()},
        'shared_previously_available_runs':len(shared),'shared_outcome_differences':differences,'reference_selection_changes':changed,
        'complete_reference_extraction':{model:{'references':16,'before_observations':sum(v['before_observations'] for v in e['videos']),
            'complete_observations':sum(v['complete_observations'] for v in e['videos']),
            'maximum_prefix_landmark_delta':max(v['prefix_max_landmark_delta'] for v in e['videos'])} for model,e in extractions.items()},
        'inputs':{name:{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in inputs.items()},
        'limits':['Availability measures usable reference comparisons, not the fraction of human reps correctly assessed.',
                  'Complete-reference runs change reference evidence duration; their gain must be reported separately from the fixed-input algorithm comparison.',
                  'The 48 source recordings and all original source timestamps remain fixed and are previously seen development data.',
                  'Median-pairwise fitting is retained only as an explicit research option; it did not add combined reference coverage.']}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['selected','selected_model_counts','shared_previously_available_runs','shared_outcome_differences','complete_reference_extraction']},indent=2))


if __name__=='__main__':main()
