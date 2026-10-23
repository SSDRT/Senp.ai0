"""Compare autonomous research candidates without turning agreement or clip labels into rep truth."""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import statistics
from extract_exercise_quality_features import DATA,FEATURES,POSE_MODELS

CRITICAL={'squat':['knee','hip'],'bicep_curl':['elbow','shoulder'],'push_up':['elbow','body_line'],'shoulder':['shoulder','elbow']}

def reference_key(row):return tuple(row[k] for k in ['reference_video_sha256','exercise','side','view'])
def source_key(row):return tuple(row[k] for k in ['video_sha256','reference_video_sha256','exercise','side','view'])

def select_reference_models(full_rows,heavy_rows):
    """Freeze one backend per reference/side using calibration availability alone, never source labels/outcomes."""
    by_model={}
    for model,rows in [('full',full_rows),('heavy',heavy_rows)]:
        grouped=collections.defaultdict(set)
        for row in rows:grouped[reference_key(row)].add(row['status'])
        if any(len(values)!=1 for values in grouped.values()):raise ValueError('Inconsistent calibration status for the same reference/model')
        by_model[model]={key:next(iter(values)) for key,values in grouped.items()}
    if by_model['full'].keys()!=by_model['heavy'].keys():raise ValueError('Reference cohorts differ')
    return {key:('full' if status=='REPLAYED' else 'heavy' if by_model['heavy'][key]=='REPLAYED' else 'unavailable')
            for key,status in by_model['full'].items()}

def summarize(rows):
    unique={reference_key(r):r['status'] for r in rows}
    return {'runs':len(rows),'replayed':sum(r['status']=='REPLAYED' for r in rows),'statuses':dict(collections.Counter(r['status'] for r in rows)),
            'unique_reference_sides':len(unique),'usable_reference_sides':sum(s=='REPLAYED' for s in unique.values()),
            'engine_outputs_not_accuracy':{key:sum(r.get('counts',{}).get(key,0) for r in rows) for key in ['qualified','correct','acceptable','rejected','unassessable']},
            'accepted_outputs_by_weak_clip_label_not_rep_truth':{label:sum(r.get('counts',{}).get('qualified',0) for r in rows if r['weak_whole_clip_label']==label) for label in ['good','bad']}}

def main():
    output=DATA/'autonomous-experiment-summary';output.mkdir(exist_ok=True)
    reports={name:json.loads((DATA/directory/'summary.json').read_text()) for name,directory in {
        'full_default':'qualified-video-replay-v5','heavy_default':'autonomous-heavy-replay',
        'full_generated_candidate':'autonomous-full-candidate-replay','heavy_generated_candidate':'autonomous-heavy-candidate-replay'}.items()}
    keys=None
    for report in reports.values():
        current={source_key(r) for r in report['rows']}
        if len(current)!=96 or keys is not None and current!=keys:raise ValueError('Source/reference pair cohorts differ')
        keys=current
    full=reports['full_default']['rows'];heavy=reports['heavy_default']['rows']
    choices=select_reference_models(full,heavy)
    heavy_by_key={source_key(r):r for r in heavy}
    selected=[heavy_by_key[source_key(r)] if choices[reference_key(r)]=='heavy' else r for r in full]
    # Coordinates/features are compared at exactly the same observed source timestamp, with no repaired frames.
    agreement=[]
    for file in sorted((DATA/'autonomous-heavy-cache').glob('*.json.gz')):
        with gzip.open(file,'rt') as stream:h=json.load(stream)
        with gzip.open(DATA/'rep-world-feature-cache'/file.name,'rt') as stream:f=json.load(stream)
        if h['source_video_sha256']!=f['source_video_sha256'] or h['model_sha256']!=POSE_MODELS['heavy'][1] or f['model_sha256']!=POSE_MODELS['full'][1]:raise ValueError('Model/source mismatch')
        if [r['timestamp_ms'] for r in h['frames']]!=[r['timestamp_ms'] for r in f['frames']]:raise ValueError('PTS mismatch')
        for side in ['left','right']:
            indices=[FEATURES.index('angle.'+side+'_'+part) for part in CRITICAL[f['exercise']]]
            differences=[];agreed=[]
            for a,b in zip(f['frames'],h['frames']):
                if any(a['features'][i] is None or b['features'][i] is None for i in indices):continue
                deltas=[abs(a['features'][i]-b['features'][i]) for i in indices];differences.extend(deltas)
                if max(deltas)<=10:agreed.append(a['timestamp_ms'])
            agreement.append({'video':f['video'],'video_sha256':f['source_video_sha256'],'side':side.upper(),
                'common_critical_angle_observations':len(differences),'absolute_angle_disagreement_median':statistics.median(differences) if differences else None,
                'agreement_only_timestamps_ms':agreed,'total_source_frames':len(f['frames']),
                'label_kind':'pose-model-agreement-only-no-correctness-or-form-label'})
    (output/'agreement-evidence.json').write_text(json.dumps(agreement,indent=2)+'\n')
    registry=[{'reference_video_sha256':key[0],'exercise':key[1],'side':key[2],'view':key[3],'selected_model':model,
               'pose_model_sha256':POSE_MODELS[model][1] if model!='unavailable' else None,
               'selection_basis':'reference-calibration-only-prefer-full-when-both-usable','release_qualified':False}
              for key,model in sorted(choices.items())]
    (output/'reference-backend-selection.json').write_text(json.dumps(registry,indent=2)+'\n')
    report={'kind':'autonomous-training-and-model-ablation-not-verified-live-precision','new_manual_labels':0,'release_qualified':False,
            'variants':{name:summarize(value['rows']) for name,value in reports.items()},
            'reference_only_selector_simulation':summarize(selected),'selection_choices':dict(collections.Counter(choices.values())),
            'experimental_parameters':reports['full_generated_candidate']['experimental_parameters'],
            'current_app_defaults_changed':False,'default_promotion_decision':'Keep current live defaults; generated-data and weak-clip evidence do not establish better live-rep precision.',
            'limits':['All current videos/participants are development or retrospective data.',
                      'Backend fallback simulation uses one fixed model for reference and live source; no per-frame model switching.',
                      'More accepted outputs or higher model agreement are not independent correctness labels.',
                      'Whole-clip classifier candidates are research models and never feed the live acceptance decision.']}
    (output/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({name:(r['replayed'],r['usable_reference_sides']) for name,r in report['variants'].items()}))
    print('Reference-only fallback simulation',report['reference_only_selector_simulation']['replayed'],'/96')

if __name__=='__main__':main()
