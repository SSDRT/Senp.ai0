"""Report observation-path tuning separately from independent live form precision."""
import collections
import hashlib
import json
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key,source_key,select_reference_models,summarize


def main():
    output=DATA/'observed-path-summary';output.mkdir(exist_ok=True)
    inputs={name:DATA/directory/'summary.json' for name,directory in {
        'before_full':'qualified-video-replay-v5','before_heavy':'autonomous-heavy-replay',
        'dense_full':'dense-curve-full-replay','dense_heavy':'dense-curve-heavy-replay',
        'strict_two_band_full':'observed-path-full-replay','strict_two_band_heavy':'observed-path-heavy-replay',
        'intermediate_without_preroll_full':'observed-path-balanced-full-replay',
        'intermediate_without_preroll_heavy':'observed-path-balanced-heavy-replay',
        'final_full':'observed-path-final-full-replay','final_heavy':'observed-path-final-heavy-replay'}.items()}
    rows={name:json.loads(path.read_text())['rows'] for name,path in inputs.items()}
    cohort={source_key(r) for r in rows['before_full']}
    assert len(cohort)==96
    assert all({source_key(r) for r in value}==cohort for value in rows.values())
    comparisons={}
    for model in ['full','heavy']:
        previous={source_key(r):r for r in rows['before_'+model]}
        current=rows['final_'+model]
        assert len(current)==len(previous)
        # Compare outcomes, availability and original timestamps, not changed policy fingerprints.
        differences=[]
        for row in current:
            old=previous[source_key(row)]
            for key in ['status','counts','evaluated_start_ms','evaluated_end_ms','frame_count']:
                if old.get(key)!=row.get(key):differences.append({'video':row['video'],'side':row['side'],'field':key,'before':old.get(key),'after':row.get(key)})
            event_keys=['started_ms','completed_ms','verdict','reasons','maximum_progress','visible_fraction','ambiguous_fraction']
            previous_events=[{k:event[k] for k in event_keys} for event in old.get('attempts',[])]
            current_events=[{k:event[k] for k in event_keys} for event in row.get('attempts',[])]
            if len(previous_events)!=len(current_events):differences.append({'video':row['video'],'side':row['side'],'field':'attempt_count'})
            for index,(old_event,new_event) in enumerate(zip(previous_events,current_events)):
                for key in event_keys:
                    if old_event[key]!=new_event[key]:differences.append({'video':row['video'],'side':row['side'],'attempt_index':index,
                        'field':key,'before':old_event[key],'after':new_event[key]})
        comparisons[model]={'compared_runs':len(current),'outcome_differences':differences}
    def selected(full,heavy):
        choices=select_reference_models(full,heavy);by_key={source_key(r):r for r in heavy}
        return choices,[by_key[source_key(r)] if choices[reference_key(r)]=='heavy' else r for r in full]
    before_choices,before_selected=selected(rows['before_full'],rows['before_heavy'])
    choices,after_selected=selected(rows['final_full'],rows['final_heavy'])
    registry=[dict(zip(['reference_video_sha256','exercise','side','view'],key),selected_model=value,
                   selection_basis='reference-calibration-only-full-first',observed_path_required=True,release_qualified=False)
              for key,value in sorted(choices.items())]
    (output/'reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n')
    mechanism=json.loads((DATA/'observed-path-mechanism.json').read_text())
    negatives=[r for r in mechanism['rows'] if not r['scenario'].startswith('complete_')]
    positives=[r for r in mechanism['rows'] if r['scenario'].startswith('complete_')]
    report={'kind':'observed-path-mechanism-and-development-regression-not-human-form-precision',
        'baseline':'8ccfb0b','new_human_annotations':0,'release_qualified':False,'live_rep_precision':None,
        'mechanism':{'incomplete_observation_controls':len(negatives),
                     'endpoint_only_false_counts':sum(r['counterfactual_endpoint_only_accepted'] for r in negatives),
                     'observed_path_false_counts':sum(r['observed_path_accepted'] for r in negatives),
                     'complete_motion_controls':len(positives),'complete_controls_accepted_before':sum(r['counterfactual_endpoint_only_accepted'] for r in positives),
                     'complete_controls_accepted_after':sum(r['observed_path_accepted'] for r in positives),'failures':mechanism['failures']},
        'variants':{name:summarize(value) for name,value in rows.items()},'same_cohort_comparisons':comparisons,
        'selector_before':summarize(before_selected),'selector_after':summarize(after_selected),
        'reference_selection_unchanged':choices==before_choices,'selected_models':dict(collections.Counter(choices.values())),
        'inputs':{name:{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in inputs.items()},
        'decision':'Enable a directly observed intermediate pose in each direction, including armed pre-departure motion. Keep six curve nodes and all existing angle/range/dwell/confidence thresholds.',
        'discarded_candidates':['Ten-node curves did not increase calibration coverage and changed accepted outputs in both clip-label classes.',
            'Two separated intermediate bands and one-intermediate evidence without pre-departure retention reduced development availability or acceptance. These are frozen prototype results; only the final and dense-curve variants remain configurable.'],
        'limits':['Generated controls describe observed trajectory coverage, not human technique labels.',
                  'No independent live-rep precision percentage is established.',
                  'Development source videos and their weak clip labels are unchanged; unchanged counts are regression evidence, not a new accuracy gain.',
                  'The policy cannot exclude every erroneous high-confidence intermediate pose or resolve depth/person/view ambiguity.']}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['mechanism','reference_selection_unchanged','selected_models']},indent=2))


if __name__=='__main__':main()
