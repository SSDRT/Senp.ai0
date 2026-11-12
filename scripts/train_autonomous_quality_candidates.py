"""Autonomously fit research clip-quality candidates using existing CC BY dataset labels.

No new annotation or per-rep labels. Historical audit participants are already seen,
so this reports retrospective subject-separated diagnostics, never fresh live accuracy.
"""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import warnings
import joblib
import numpy as np
from sklearn.base import clone
from sklearn.ensemble import ExtraTreesClassifier,HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import balanced_accuracy_score,confusion_matrix,roc_auc_score
from sklearn.model_selection import GroupKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from extract_exercise_quality_features import DATA,FEATURES,MODEL_SHA,ROOT,SCHEMA
from train_exercise_quality_model import IDS,predict,windows

STATISTICS=['q10','median','q90','mad','coverage','speed_q90']
BODY_PARTS={
    'abs':['hip','shoulder','hand_to_hip','body_line'],
    'back':['hip','shoulder','elbow','body_line','hand_to_shoulder'],
    'bicep_curl':['elbow','shoulder','elbow_to_hip','hand_to_shoulder'],
    'push_up':['elbow','shoulder','body_line','hip'],
    'shoulder':['elbow','shoulder','body_line','hand_to_shoulder'],
    'squat':['knee','hip','ankle','foot_width','knee_width'],
    'tricep':['elbow','shoulder','hand_to_shoulder'],
}

def aggregate_clip(video):
    """Observed scalar statistics; imputation belongs only inside each training fold."""
    result=[];usable=0
    for index in range(len(FEATURES)):
        observed=[(f['timestamp_ms'],f['features'][index]) for f in video['frames'] if f['features'][index] is not None]
        coverage=len(observed)/len(video['frames']) if video['frames'] else 0.0
        if len(observed)<4:result.extend([np.nan,np.nan,np.nan,np.nan,coverage,np.nan]);continue
        usable+=1;values=np.asarray([v for _,v in observed],dtype=float);median=float(np.median(values))
        speed=[abs(b-a)*1000/(tb-ta) for (ta,a),(tb,b) in zip(observed,observed[1:]) if 0<tb-ta<=750]
        result.extend([*np.quantile(values,[.1,.5,.9]).tolist(),float(np.median(abs(values-median))),coverage,
                       float(np.quantile(speed,.9)) if speed else np.nan])
    return result,usable

def metrics(y,scores,threshold=.5):
    predictions=np.asarray(scores)>=threshold;y=np.asarray(y,dtype=int)
    tn,fp,fn,tp=confusion_matrix(y,predictions,labels=[0,1]).ravel()
    return {'clips':len(y),'bad_precision':float(tp/(tp+fp)) if tp+fp else None,
            'bad_recall':float(tp/(tp+fn)) if tp+fn else None,'good_false_alert_rate':float(fp/(fp+tn)) if fp+tn else None,
            'balanced_accuracy':float(balanced_accuracy_score(y,predictions)) if len(set(y))==2 else None,
            'roc_auc':float(roc_auc_score(y,scores)) if len(set(y))==2 else None,
            'tn':int(tn),'fp':int(fp),'fn':int(fn),'tp':int(tp)}

def candidates():
    impute=lambda:SimpleImputer(strategy='median',add_indicator=True,keep_empty_features=True)
    return {
        'logistic_C0.1':make_pipeline(impute(),StandardScaler(),LogisticRegression(C=.1,class_weight='balanced',max_iter=1500,random_state=20261023)),
        'logistic_C1':make_pipeline(impute(),StandardScaler(),LogisticRegression(C=1,class_weight='balanced',max_iter=1500,random_state=20261023)),
        'extra_trees':make_pipeline(impute(),ExtraTreesClassifier(n_estimators=120,max_depth=8,min_samples_leaf=4,max_features=.7,class_weight='balanced',n_jobs=1,random_state=20261023)),
        'gradient_boosting':make_pipeline(impute(),HistGradientBoostingClassifier(max_iter=100,max_leaf_nodes=7,min_samples_leaf=12,class_weight='balanced',random_state=20261023)),
    }

def main():
    output=DATA/'autonomous-quality-training';output.mkdir(exist_ok=True)
    manifest=json.loads((DATA/'corpus-video-manifest.json').read_text())
    sources={r['file']:r for r in manifest['videos']}
    previous=json.loads((ROOT/'models/exercise_quality.training-report.json').read_text())
    train_subjects=set(previous['train_subjects']);validation_subjects=set(previous['validation_subjects']);audit_subjects=set(previous['test_subjects'])
    if train_subjects&validation_subjects or train_subjects&audit_subjects or validation_subjects&audit_subjects:raise ValueError('Subject overlap')
    baseline=json.loads((ROOT/'core-motion/src/main/resources/ai/senp/motion/learned-exercise-quality.json').read_text())
    baseline_models={m['dataset_exercise']:m for m in baseline['exercises']}
    rows=[];excluded=[]
    paths=sorted((DATA/'world-feature-cache').glob('*.json.gz'))
    if len(paths)!=1042:raise ValueError('Expected all 1042 original coarse-cache clips')
    for index,path in enumerate(paths):
        with gzip.open(path,'rt') as stream:video=json.load(stream)
        source=sources[video['video']]
        if video['schema']!=SCHEMA or video['model_sha256']!=MODEL_SHA or video['feature_names']!=FEATURES:raise ValueError('Feature provenance mismatch')
        if video['source_video_sha256']!=source['sha256'] or any(video[k]!=source[k] for k in ['exercise','subject','quality','view']):raise ValueError('Label identity mismatch')
        values,usable=aggregate_clip(video)
        if usable<6:
            excluded.append({'video':video['video'],'subject':video['subject'],'exercise':video['exercise'],'quality':video['quality'],'reason':'fewer_than_six_observed_features'});continue
        old=baseline_models.get(video['exercise']);old_scores=[predict(old,w['x'])[0] for w in windows(video)] if old else []
        rows.append({'video':video['video'],'subject':video['subject'],'exercise':video['exercise'],'view':video['view'],
                     'y':int(video['quality']=='bad'),'x':values,'baseline_score':float(np.mean(old_scores)) if old_scores else None})
        if (index+1)%200==0:print('Loaded',index+1,'/1042 clips',flush=True)
    report={'kind':'existing-clip-label-autonomous-training-not-live-rep-precision','new_manual_labels':0,
            'dataset_doi':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0','input_videos':1042,'usable_videos':len(rows),'excluded_videos':excluded,
            'train_subjects':sorted(train_subjects),'validation_subjects':sorted(validation_subjects),'retrospective_audit_subjects':sorted(audit_subjects),
            'model_selection':'Four grouped training folds only; validation threshold only; historical audit scored after model/threshold freezing',
            'release_qualified':False,'source_model_sha256':MODEL_SHA,'exercises':[],
            'limits':['Historical audit participants were previously inspected; this is not a fresh held-out accuracy test.',
                      'Existing labels describe entire clips; no frame/rep/fault labels are invented.',
                      'Coarse 500ms extraction cannot validate immediate live fault timing.',
                      'Missing scalar feature imputation is for this offline classifier; no landmark coordinates are repaired or certified.',
                      'These whole-clip models are never imported into the live rep acceptance path.']}
    predictions=[]
    for exercise,exercise_id in IDS.items():
        selected=[r for r in rows if r['exercise']==exercise]
        parts=[[r for r in selected if r['subject'] in subjects] for subjects in [train_subjects,validation_subjects,audit_subjects]]
        if any(len({r['y'] for r in part})<2 for part in parts):
            report['exercises'].append({'exercise':exercise_id,'status':'INSUFFICIENT_EXISTING_LABELLED_DATA'});continue
        indices=[i*len(STATISTICS)+j for i,name in enumerate(FEATURES) if any(part in name for part in BODY_PARTS[exercise]) for j in range(len(STATISTICS))]
        feature_names=[FEATURES[i//len(STATISTICS)]+'.'+STATISTICS[i%len(STATISTICS)] for i in indices]
        matrices=[np.asarray([[r['x'][i] for i in indices] for r in part],dtype=float) for part in parts]
        ys=[np.asarray([r['y'] for r in part],dtype=int) for part in parts];groups=[r['subject'] for r in parts[0]]
        folds=list(GroupKFold(n_splits=4).split(matrices[0],ys[0],groups))
        scores={}
        for name,estimator in candidates().items():
            fold_scores=[]
            for fitting,checking in folds:
                if len(set(ys[0][fitting]))<2 or len(set(ys[0][checking]))<2:raise ValueError('Grouped fold missing class')
                model=clone(estimator).fit(matrices[0][fitting],ys[0][fitting])
                fold_scores.append(float(roc_auc_score(ys[0][checking],model.predict_proba(matrices[0][checking])[:,1])))
            scores[name]={'grouped_training_fold_auc':fold_scores,'mean_auc':float(np.mean(fold_scores))}
        chosen=max(sorted(scores),key=lambda name:scores[name]['mean_auc'])
        fitted=clone(candidates()[chosen]).fit(matrices[0],ys[0])
        validation=fitted.predict_proba(matrices[1])[:,1]
        threshold_options=[]
        for threshold in sorted(set(float(s) for s in validation)):
            m=metrics(ys[1],validation,threshold)
            alerted=[r for r,s in zip(parts[1],validation) if s>=threshold]
            if m['bad_precision'] is not None and m['bad_precision']>=.95 and len(alerted)>=10 and len({r['subject'] for r in alerted})>=3:
                threshold_options.append((m['bad_recall'],threshold))
        threshold=max(threshold_options)[1] if threshold_options else None
        # Candidate selection and the validation threshold are frozen before looking at audit labels/results.
        frozen=output/(exercise_id+'.joblib');joblib.dump({'estimator':fitted,'feature_names':feature_names,'threshold':threshold,
            'exercise':exercise_id,'release_qualified':False,'task':'whole-clip-quality-not-rep-acceptance'},frozen,compress=3)
        audit=fitted.predict_proba(matrices[2])[:,1]
        common=[i for i,r in enumerate(parts[2]) if r['baseline_score'] is not None]
        common_y=ys[2][common];new_common=audit[common];old_common=[parts[2][i]['baseline_score'] for i in common]
        result={'exercise':exercise_id,'selected_candidate':chosen,'candidate_selection':scores,'feature_count':len(indices),
                'train_clips':len(parts[0]),'validation_clips':len(parts[1]),'retrospective_audit_clips':len(parts[2]),
                'validation_alert_threshold':threshold,'validation_at_half':metrics(ys[1],validation),
                'audit_at_half':metrics(ys[2],audit),'audit_at_validation_threshold':metrics(ys[2],audit,threshold) if threshold is not None else None,
                'same_clips_comparison':{'clips':len(common),'previous_model':metrics(common_y,old_common) if common else None,
                                         'new_candidate':metrics(common_y,new_common) if common else None},
                'audit_by_view':{view:metrics([r['y'] for r in parts[2] if r['view']==view],[s for r,s in zip(parts[2],audit) if r['view']==view])
                                 for view in sorted({r['view'] for r in parts[2]})},
                'artifact_sha256':hashlib.sha256(frozen.read_bytes()).hexdigest(),'artifact_bytes':frozen.stat().st_size,'release_qualified':False}
        (output/(exercise_id+'.features.json')).write_text(json.dumps({'features':feature_names,'statistics':STATISTICS,'release_qualified':False},indent=2)+'\n')
        report['exercises'].append(result)
        predictions.extend({'video':r['video'],'subject':r['subject'],'exercise':exercise_id,'view':r['view'],'existing_clip_label':r['y'],
                            'uncalibrated_bad_clip_score':float(s),'baseline_score':r['baseline_score']} for r,s in zip(parts[2],audit))
        print('Fitted',exercise_id,chosen,'audit clip AUC',result['audit_at_half']['roc_auc'],'validation threshold',threshold,flush=True)
    (output/'training-report.json').write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
    (output/'retrospective-predictions.json').write_text(json.dumps(predictions,indent=2,allow_nan=False)+'\n')
    print('Finished autonomous research training; live release remains unqualified',flush=True)

if __name__=='__main__':
    with warnings.catch_warnings():
        warnings.filterwarnings('ignore',message='All-NaN slice encountered')
        main()
