"""Compare the first-frame peak correction with frozen schema-2 development replays."""
import collections
import hashlib
import json
from extract_exercise_quality_features import DATA
from summarize_autonomous_pose_experiments import reference_key,source_key,select_reference_models,summarize


def main():
    output=DATA/'departure-peak-summary';output.mkdir(exist_ok=True)
    inputs={name:DATA/directory/'summary.json' for name,directory in {
        'before_full':'observed-path-final-full-replay','before_heavy':'observed-path-final-heavy-replay',
        'after_full':'departure-peak-full-replay','after_heavy':'departure-peak-heavy-replay'}.items()}
    rows={name:json.loads(path.read_text())['rows'] for name,path in inputs.items()}
    comparisons={}
    for model in ['full','heavy']:
        old={source_key(r):r for r in rows['before_'+model]}
        current=rows['after_'+model]
        assert len(old)==len(current)==96 and set(old)=={source_key(r) for r in current}
        differences=[]
        for row in current:
            previous=old[source_key(row)]
            for key in ['video_sha256','reference_video_sha256','frame_count','evaluated_start_ms','evaluated_end_ms']:
                assert previous[key]==row[key],(model,key,source_key(row))
            for key in ['status','counts','observed_reference_cycles','reference_self_replay','attempts','faults']:
                if previous.get(key)!=row.get(key):
                    differences.append({'video':row['video'],'side':row['side'],'field':key,'before':previous.get(key),'after':row.get(key)})
        comparisons[model]={'compared_runs':96,'outcome_differences':differences}
    def selected(full,heavy):
        choices=select_reference_models(full,heavy);by_key={source_key(r):r for r in heavy}
        return choices,[by_key[source_key(r)] if choices[reference_key(r)]=='heavy' else r for r in full]
    before_choices,before=selected(rows['before_full'],rows['before_heavy'])
    after_choices,after=selected(rows['after_full'],rows['after_heavy'])
    def counted_events(selected_rows):
        return {source_key(row):[{key:event[key] for key in ['started_ms','completed_ms','verdict','reasons']}
                for event in row.get('attempts',[]) if event['verdict'] in ['CORRECT','ACCEPTABLE']] for row in selected_rows}
    accepted_unchanged=counted_events(before)==counted_events(after)
    generated_paths={'before':DATA/'departure-peak-baseline.json','after':DATA/'departure-peak-controls.json'}
    generated={name:json.loads(path.read_text()) for name,path in generated_paths.items()}
    assert generated['after']['scenarios']==384 and generated['after']['failures']==0
    def groups(report):
        result={}
        for row in report['rows']:
            group=result.setdefault(row['scenario']+('_spike' if row['departure_spike'] else '_control'),collections.Counter())
            group.update(cases=1,qualified=row['qualified'],rejected=row['rejected'],unassessable=row['unassessable'],
                         premature_return=int(row['premature_return_phase']))
        return result
    report={'kind':'adjacent-departure-peak-support-tuning-not-human-form-precision','baseline_commit':'8a01f1cff805da1b7dabb6118613c134de77bc80',
        'reference_policy_schema':'qualified-reference-reps/3','new_human_annotations':0,'landmark_weights_retrained':False,
        'release_qualified':False,'live_rep_precision':None,
        'generated':{name:{'scenarios':value['scenarios'],'failures':value['failures'],'groups':groups(value)} for name,value in generated.items()},
        'variants':{name:summarize(value) for name,value in rows.items()},'same_cohort_comparisons':comparisons,
        'selector_before':summarize(before),'selector_after':summarize(after),'reference_selection_unchanged':before_choices==after_choices,
        'counted_event_boundaries_and_verdicts_unchanged':accepted_unchanged,
        'selected_models':dict(collections.Counter(after_choices.values())),
        'inputs':{name:{'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,path in (inputs|generated_paths).items()},
        'limits':['Generated single-frame corruption is a declared engineering control, not a human exercise judgement.',
                  'The existing development videos are not a fresh held-out cohort and have no independently labelled repetitions.',
                  'Two adjacent erroneous poses can still satisfy peak support; confidence is not anatomical truth.']}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['generated','selector_before','selector_after','reference_selection_unchanged','selected_models']},indent=2))
    print('Difference groups:',{m:len(v['outcome_differences']) for m,v in comparisons.items()})


if __name__=='__main__':main()
