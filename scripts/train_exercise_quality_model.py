"""Fit optional per-exercise form-quality diagnostics from subject-disjoint videos.

Labels are the dataset creators' whole-clip good/bad labels, not expert annotated
fault times, forces or landmark truth. Never auto-prescribe anatomical corrections.
"""
import argparse
import collections
import gzip
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import random
import subprocess
import warnings
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from extract_exercise_quality_features import DATA,FEATURES,MODEL_SHA,SCHEMA

ROOT=Path(__file__).resolve().parents[1]
IDS={'abs':'standing_side_bend','back':'bent_over_row','bicep_curl':'biceps_curl','push_up':'push_up',
     'shoulder':'overhead_press','squat':'bodyweight_squat','tricep':'overhead_triceps_extension'}

def windows(video):
    buffer=[];last=None
    for frame in video['frames']:
        now=frame['timestamp_ms']
        if last is not None and now-last>750:buffer=[]
        last=now
        if all(value is None for value in frame['features']):buffer=[];continue
        buffer.append(frame);buffer=[r for r in buffer if now-r['timestamp_ms']<=2400]
        if len(buffer)<4 or now-buffer[0]['timestamp_ms']<2000:continue
        values=[];available=0
        for index in range(25):
            samples=[r['features'][index] for r in buffer if r['features'][index] is not None]
            if len(samples)>=3:
                values.extend([*np.quantile(samples,[0.1,0.5,0.9]).tolist(),len(samples)/len(buffer)]);available+=1
            else:values.extend([math.nan,math.nan,math.nan,len(samples)/len(buffer)])
        if available/25<0.60:continue
        yield {'x':values,'subject':video['subject'],'exercise':video['exercise'],'quality':video['quality'],
               'view':video['view'],'video':video['video'],'timestamp_ms':now,'frames':list(buffer)}

def predict(model,x):
    data=np.asarray(x,dtype=float)
    filled=np.where(np.isfinite(data),data,np.asarray(model['impute']))
    standardized=(filled-np.asarray(model['mean']))/np.asarray(model['scale'])
    outside=float(np.mean(np.abs(standardized)>6))
    logit=float(np.dot(standardized,np.asarray(model['weights']))+model['bias'])
    score=1/(1+math.exp(-max(-700,min(700,logit))))
    return score,outside

def metrics(rows,scores,threshold,include_auc=True):
    y=np.asarray([r['quality']=='bad' for r in rows],dtype=bool);p=np.asarray(scores,dtype=float)
    alerts=np.zeros(len(rows),dtype=bool) if threshold is None else p>=threshold
    tp=int(np.sum(alerts&y));fp=int(np.sum(alerts&~y));fn=int(np.sum(~alerts&y));tn=int(np.sum(~alerts&~y))
    return {'windows':len(rows),'tp':tp,'fp':fp,'fn':fn,'tn':tn,'precision':tp/(tp+fp) if tp+fp else None,
            'recall':tp/(tp+fn) if tp+fn else None,'auc':float(roc_auc_score(y,p)) if include_auc and len(set(y))==2 else None}

def video_metrics(rows,scores,threshold):
    grouped=collections.defaultdict(list)
    for row,score in zip(rows,scores):grouped[row['video']].append((row,score))
    return metrics([items[0][0] for items in grouped.values()],
                   [sum(score for _,score in items)/len(items) for items in grouped.values()],threshold)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--allow-partial',action='store_true');parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    caches=sorted((DATA/'world-feature-cache').glob('*.json.gz'))
    if len(caches)!=1042 and not args.allow_partial:raise ValueError(f'Expected 1042 extracted videos, found {len(caches)}')
    videos=[];all_windows=[]
    manifest=json.loads((DATA/'corpus-video-manifest.json').read_text())
    source_videos={v['file']:v for v in manifest['videos']}
    for path in caches:
        with gzip.open(path,'rt',encoding='utf-8') as stream:video=json.load(stream)
        if video['schema']!=SCHEMA or video['model_sha256']!=MODEL_SHA or video['feature_names']!=FEATURES:raise ValueError('Incompatible feature cache')
        source=source_videos.get(video['video'])
        if source is None or source['sha256']!=video['source_video_sha256'] or any(source[k]!=video[k] for k in ['subject','exercise','quality','view']):
            raise ValueError('Feature cache provenance/label mismatch: '+str(path))
        videos.append(video);all_windows.extend(windows(video))
    subjects=sorted({v['subject'] for v in videos});random.Random(20261022).shuffle(subjects)
    test_subjects=set(subjects[:5]);validation_subjects=set(subjects[5:10]);train_subjects=set(subjects[10:])
    assert not (test_subjects&validation_subjects or test_subjects&train_subjects or validation_subjects&train_subjects)
    artifact={'schema':'learned-exercise-quality/1','feature_schema':SCHEMA,'feature_names':FEATURES,
              'dataset_doi':'10.17632/kgbb3yn47p.3','pose_model_sha256':MODEL_SHA,'release_approved':False,
              'window_ms':2400,'minimum_span_ms':2000,'sample_period_ms':500,'maximum_gap_ms':750,
              'minimum_samples':4,'minimum_feature_samples':3,'minimum_feature_confidence':0.65,'minimum_feature_coverage':0.60,
              'maximum_outlier_fraction':0.10,'outlier_z':6.0,'exercises':[],'unsupported_exercises':[]}
    report={'kind':'subject-disjoint-clip-labelled-world-pose-quality-diagnostics-not-landmark-accuracy',
            'dataset_doi':artifact['dataset_doi'],'license':'CC BY 4.0','source_videos':len(videos),'windows':len(all_windows),
            'split_seed':20261022,'train_subjects':sorted(train_subjects),'validation_subjects':sorted(validation_subjects),'test_subjects':sorted(test_subjects),
            'full_archive_sha256_verified':False,'video_integrity':'ZIP CRC + per-video SHA-256; remote archive index over HTTPS',
            'maximum_sampled_duration_ms':24000,'sample_period_ms':500,
            'runtime':{p:importlib.metadata.version(p) for p in ['numpy','scikit-learn','mediapipe','Pillow']},
            'ffmpeg':subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0],
            'research_runtime_patch':json.loads((DATA/'research-runtime-patch.json').read_text()),
            'exercises':[],'limits':['Labels are whole-clip good/bad, not time-resolved expert fault annotations.',
                                    'Pose landmarks are model outputs, not independent keypoint ground truth.',
                                    'Overlapping windows are correlated; do not treat them as independent people.',
                                    'Scores are uncalibrated and must not be described as form probabilities.',
                                    'Thresholds are chosen on validation subjects only; no phone or clinical qualification.',
                                    'Runtime modality/input resolution and false detections need independent validation.']}
    parity=[];geometry_parity=[]
    for dataset_name,exercise_id in IDS.items():
        rows=[r for r in all_windows if r['exercise']==dataset_name]
        train=[r for r in rows if r['subject'] in train_subjects]
        validation=[r for r in rows if r['subject'] in validation_subjects]
        test=[r for r in rows if r['subject'] in test_subjects]
        counts_by_split={'train':len(train),'validation':len(validation),'test':len(test)}
        if any(not part or len({r['quality'] for r in part})<2 for part in [train,validation,test]):
            audit={'exercise_id':exercise_id,'dataset_exercise':dataset_name,'status':'UNSUPPORTED_INSUFFICIENT_RELIABLE_POSE',
                   'window_counts':counts_by_split,'reason':'At least one subject split lacks reliable windows or both quality classes at the unchanged confidence/coverage gates.'}
            report['exercises'].append(audit);artifact['unsupported_exercises'].append(audit)
            print(exercise_id,json.dumps(audit),flush=True);continue
        x=np.asarray([r['x'] for r in train]);y=np.asarray([r['quality']=='bad' for r in train],dtype=int)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',RuntimeWarning);impute=np.nanmedian(x,axis=0)
        impute=np.where(np.isfinite(impute),impute,0);filled=np.where(np.isfinite(x),x,impute)
        mean=filled.mean(axis=0);scale=np.maximum(filled.std(axis=0),0.001)
        counts=collections.Counter(r['video'] for r in train)
        weights=np.asarray([1/counts[r['video']] for r in train]);weights*=len(weights)/weights.sum()
        fitted=LogisticRegression(C=1.0,class_weight='balanced',max_iter=1000,random_state=20261022).fit((filled-mean)/scale,y,sample_weight=weights)
        model={'exercise_id':exercise_id,'dataset_exercise':dataset_name,'impute':impute.tolist(),'mean':mean.tolist(),'scale':scale.tolist(),
               'weights':fitted.coef_[0].tolist(),'bias':float(fitted.intercept_[0]),'review_threshold':None}
        # Round before scoring so validation and exported-runtime metrics describe identical parameters.
        model=json.loads(json.dumps(model),parse_float=lambda s:round(float(s),9))
        validation_predictions=[predict(model,r['x']) for r in validation]
        val_scores=[s if outlier<=artifact['maximum_outlier_fraction'] else -1.0 for s,outlier in validation_predictions]
        candidates=sorted(set(s for s in val_scores if s>=0.5));qualified=[]
        for threshold in candidates:
            m=metrics(validation,val_scores,threshold,include_auc=False)
            selected=[r for r,s in zip(validation,val_scores) if s>=threshold]
            if m['precision'] is not None and m['precision']>=0.95 and m['tp']+m['fp']>=10 and len({r['subject'] for r in selected})>=2 and len({r['video'] for r in selected})>=3:
                qualified.append((m['recall'], -threshold,threshold))
        if qualified:model['review_threshold']=max(qualified)[2]
        test_predictions=[predict(model,r['x']) for r in test]
        test_scores=[s for s,_ in test_predictions]
        safe_scores=[score if outside<=artifact['maximum_outlier_fraction'] else -1.0 for score,outside in test_predictions]
        model['validation_qualified']=model['review_threshold'] is not None
        artifact['exercises'].append(model)
        audit={'exercise_id':exercise_id,'dataset_exercise':dataset_name,'status':'RESEARCH_ONLY','training_windows':len(train),'validation_windows':len(validation),'test_windows':len(test),
               'training_videos':len({r['video'] for r in train}),'validation_videos':len({r['video'] for r in validation}),'test_videos':len({r['video'] for r in test}),
               'validation_selected_threshold':model['review_threshold'],'validation':metrics(validation,val_scores,model['review_threshold']),
               'test_window_metrics':metrics(test,safe_scores,model['review_threshold']),
               'test_raw_window_auc':float(roc_auc_score([r['quality']=='bad' for r in test],test_scores)),
               'validation_outlier_windows':sum(outside>artifact['maximum_outlier_fraction'] for _,outside in validation_predictions),
               'test_video_mean_score_metrics':video_metrics(test,test_scores,model['review_threshold']),
               'test_outlier_windows':sum(outside>artifact['maximum_outlier_fraction'] for _,outside in test_predictions),
               'test_by_view':{view:metrics([r for r in test if r['view']==view],[s for r,s in zip(test,safe_scores) if r['view']==view],model['review_threshold']) for view in ['front','side','diagonal']}}
        report['exercises'].append(audit)
        chosen=[]
        for quality in ['good','bad']:
            match=next((r for r in test if r['quality']==quality),None)
            if match:chosen.append(match)
        for row in chosen:
            score,outlier=predict(model,row['x'])
            parity.append({'exercise_id':exercise_id,'video':row['video'],'subject':row['subject'],'quality':row['quality'],
                           'frames':[{'timestamp_ms':f['timestamp_ms'],'features':f['features']} for f in row['frames']],
                           'aggregated':[None if not math.isfinite(v) else v for v in row['x']],
                           'score':score,'outlier_fraction':outlier})
            raw=row['frames'][-1]
            geometry_parity.append({'exercise_id':exercise_id,'video':row['video'],'timestamp_ms':raw['timestamp_ms'],
                                    'feature_names':FEATURES,'features':raw['features'],'landmarks':raw['landmarks']})
        print(exercise_id,json.dumps(audit['test_window_metrics']),flush=True)
    output=ROOT/'core-motion/src/main/resources/ai/senp/motion/learned-exercise-quality.json'
    serialized=json.dumps(artifact,indent=2,sort_keys=True)+'\n'
    fixture=ROOT/'core-motion/src/test/resources/ai/senp/motion/learned-quality-parity.json'
    parity_text=json.dumps(parity,indent=2,sort_keys=True,allow_nan=False)+'\n'
    geometry_fixture=ROOT/'validation-app/src/test/resources/learned-quality-geometry-parity.json'
    geometry_text=json.dumps(geometry_parity,indent=2,sort_keys=True,allow_nan=False)+'\n'
    if args.check:
        assert output.read_text()==serialized,'Quality model is stale'
        assert fixture.read_text()==parity_text,'Quality parity fixture is stale'
        assert geometry_fixture.read_text()==geometry_text,'Quality geometry fixture is stale'
    else:
        output.write_text(serialized,newline='\n');fixture.write_text(parity_text,newline='\n')
        geometry_fixture.parent.mkdir(parents=True,exist_ok=True);geometry_fixture.write_text(geometry_text,newline='\n')
    report['artifact_sha256']=hashlib.sha256(output.read_bytes()).hexdigest()
    (DATA/'quality-training-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('TRAIN quality model',report['artifact_sha256'],flush=True)

if __name__=='__main__':main()
