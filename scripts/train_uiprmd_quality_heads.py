"""Development-only leave-one-subject-out attempt-quality research.

Publisher rep labels supervise whole completed attempts, never per-frame faults.
The heads may veto an existing qualified result in this offline experiment only;
they cannot create counts or override production evidence/range/visibility gates.
"""
import hashlib
import json
from pathlib import Path
import zipfile
from contextlib import ExitStack
import joblib
import numpy as np
import sklearn
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from fetch_uiprmd import ROOT,DATA,checksum
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from evaluate_uiprmd_development import match_events,evaluate_rows,QUALIFIED
from uiprmd_quality_features import SCHEMA,frame_features,aggregate


def model(name):
    imputer=SimpleImputer(strategy='median',keep_empty_features=True)
    if name in {'logistic','logistic_C0_1'}:
        strength=1.0 if name=='logistic' else .1
        return make_pipeline(imputer,StandardScaler(),LogisticRegression(C=strength,class_weight='balanced',max_iter=2000,random_state=17))
    if name=='forest':
        return make_pipeline(imputer,RandomForestClassifier(n_estimators=256,min_samples_leaf=4,max_features='sqrt',
                                                           class_weight='balanced',random_state=17,n_jobs=1))
    raise ValueError('Unknown frozen candidate')


def split_indices(subjects,held_out):
    subjects=np.asarray(subjects)
    if held_out not in {'01','02','03','04'} or not set(subjects)<={'01','02','03','04'}:
        raise PermissionError('Only the frozen development subjects may be fitted or predicted')
    train=np.flatnonzero(subjects!=held_out);test=np.flatnonzero(subjects==held_out)
    if not len(train) or not len(test) or set(subjects[train]) & set(subjects[test]):
        raise ValueError('Invalid subject-disjoint development fold')
    return train,test


def load_attempts():
    protocol=json.loads((ROOT/'models/uiprmd-evaluation-protocol.json').read_text())
    allowed=set(protocol['subjects']['development'])
    if allowed!={'01','02','03','04'}:raise ValueError('Frozen development split changed')
    acquisition=json.loads((DATA/'acquisition.json').read_text())
    replay_path=DATA/'development-replay.json';replay=json.loads(replay_path.read_text())
    if replay['compiler']!='qualified-rep-compiler/4':raise ValueError('Expected the current frozen rep engine')
    samples=[];reads=[];feature_names=None
    with ExitStack() as stack:
        archives={}
        for name in ['Movements.zip','Incorrect Movements.zip']:
            receipt=next(r for r in acquisition['files'] if r['file']==name)
            if checksum(DATA/name,'sha256')!=receipt['sha256']:raise ValueError('Publisher archive changed')
            archives[name]=stack.enter_context(zipfile.ZipFile(DATA/name))
        for i,row in enumerate(replay['rows']):
            if row['subject'] not in allowed:raise PermissionError('Held-out trajectory in replay')
            archive_name='Movements.zip' if row['correct'] else 'Incorrect Movements.zip'
            raw=read_development_member(archives[archive_name],row['source_member'],allowed)
            digest=hashlib.sha256(raw).hexdigest()
            if digest!=row['source_sha256']:raise ValueError('Replay source changed')
            reads.append({'archive':archive_name,'member':row['source_member'],'sha256':digest})
            array,_=parse_publisher_array(raw,'Vicon');values=frame_features(array)
            matches,_=match_events(row['episodes'],row['attempts'])
            for rep_index,match in matches.items():
                j=match['attempt_index'];attempt=row['attempts'][j]
                # Sample only actual observations at/before the emitted completion.
                first=int(np.ceil(attempt['started_ms']/10));last=min(len(array)-1,int(np.floor(attempt['completed_ms']/10)))
                vector,names=aggregate(values,first,last)
                if feature_names is not None and names!=feature_names:raise ValueError('Feature schema changed')
                feature_names=names
                samples.append({'row':i,'attempt':j,'subject':row['subject'],'movement':row['movement'],
                                'bad':not row['episodes'][rep_index]['correct'],'vector':vector})
    return replay,samples,reads,feature_names


def evaluate_veto(replay,samples,scores,threshold=.5):
    if len(samples)!=len(scores) or len({(s['row'],s['attempt']) for s in samples})!=len(samples):
        raise ValueError('Exactly one score per matched attempt is required')
    if not 0<=threshold<=1:raise ValueError('Invalid veto threshold')
    rows=json.loads(json.dumps(replay['rows']))
    for sample,score in zip(samples,scores):
        if not np.isfinite(score) or not 0<=score<=1:raise ValueError('Every matched attempt needs one finite probability score')
        attempt=rows[sample['row']]['attempts'][sample['attempt']]
        if attempt['verdict'] in QUALIFIED and score>=threshold:attempt['verdict']='REJECTED'
    report=evaluate_rows(rows)
    result={k:v for k,v in report.items() if k!='rep_results'}
    result['per_movement']={movement:{k:v for k,v in evaluate_rows([r for r in rows if r['movement']==movement]).items()
                                    if k!='rep_results'} for movement in sorted({r['movement'] for r in rows})}
    return result


def main():
    replay,samples,reads,names=load_attempts()
    x=np.asarray([s['vector'] for s in samples]);y=np.asarray([s['bad'] for s in samples],dtype=int)
    subjects=np.asarray([s['subject'] for s in samples]);movements=np.asarray([s['movement'] for s in samples])
    candidates={};baseline=evaluate_rows(replay['rows'])
    model_directory=DATA/'quality-head-development-models';model_directory.mkdir(exist_ok=True)
    for name in ['logistic','logistic_C0_1','forest']:
        scores=np.full(len(samples),np.nan);folds=[]
        for movement in ['m01','m07']:
            selected=np.flatnonzero(movements==movement)
            for subject in ['01','02','03','04']:
                a,b=split_indices(subjects[selected],subject);train=selected[a];test=selected[b]
                classifier=model(name);classifier.fit(x[train],y[train])
                scores[test]=classifier.predict_proba(x[test])[:,list(classifier.classes_).index(1)]
                folds.append({'movement':movement,'test_subject':subject,'train_subjects':sorted(set(subjects[train])),
                              'train_attempts':len(train),'test_attempts':len(test)})
        prediction=scores>=.5
        candidates[name]={'fixed_bad_score_veto_threshold':.5,'out_of_fold_classification_accuracy':float(np.mean(prediction==y)),
                          'completed_attempts_scored':len(samples),'folds':folds,'veto_only_continuous_rep_metrics':evaluate_veto(replay,samples,scores),
                          'out_of_fold_scores':[{'row':s['row'],'attempt':s['attempt'],'subject':s['subject'],'movement':s['movement'],
                                                'original_rep_bad':s['bad'],'bad_score_uncalibrated':float(score)} for s,score in zip(samples,scores)]}
        # Exploratory development operating points, not selected on final-test data.
        candidates[name]['development_threshold_tradeoffs']={str(t):evaluate_veto(replay,samples,scores,t) for t in [.5,.75,.9,.95]}
        trained=[]
        for movement in ['m01','m07']:
            selected=np.flatnonzero(movements==movement);fitted=model(name);fitted.fit(x[selected],y[selected])
            path=model_directory/f'{movement}-{name}.joblib';joblib.dump(fitted,path,compress=3)
            trained.append({'path':path.relative_to(ROOT).as_posix(),'sha256':checksum(path,'sha256'),
                            'training_subjects':sorted(set(subjects[selected])),'training_attempts':len(selected),'release_approved':False})
        candidates[name]['all_development_fits_not_used_for_reported_scores']=trained
    result={'schema':'uiprmd-quality-head-development/1','kind':'development-only-completed-attempt-veto-research-not-live-camera-accuracy',
            'measurement_domain':'skeletal','feature_schema':SCHEMA,'feature_names':names,'features':len(names),
            'model_parameters_recorded_before_this_fit':{'logistic':{'C':1,'class_weight':'balanced','max_iter':2000},
                'logistic_C0_1':{'C':.1,'class_weight':'balanced','max_iter':2000},
                'forest':{'trees':256,'minimum_leaf':4,'max_features':'sqrt','class_weight':'balanced'},'seed':17,'threshold':.5},
            'sklearn_version':sklearn.__version__,'numpy_version':np.__version__,'existing_independent_rep_labels':True,
            'subjects_opened':sorted(set(subjects)),'validation_trajectories_opened':False,'final_test_trajectories_opened':False,
            'network_weights_trained':False,'experimental_quality_heads_fitted':True,'production_changed':False,'promotion_approved':False,
            'readiness_points_earned':0,'source_replay_sha256':checksum(DATA/'development-replay.json','sha256'),
            'source_code_sha256':{path:checksum(ROOT/path,'sha256') for path in [
                'scripts/train_uiprmd_quality_heads.py','scripts/uiprmd_quality_features.py',
                'scripts/uiprmd_vicon.py','scripts/evaluate_uiprmd_development.py']},
            'protocol_sha256':checksum(ROOT/'models/uiprmd-evaluation-protocol.json','sha256'),
            'reads':reads,'baseline':{k:v for k,v in baseline.items() if k!='rep_results'},'candidates':candidates,
            'limitations':['Development folds are not the final subject-disjoint test.',
                'Publisher labels describe whole repetitions, not joint fault onsets; scoring occurs only after causal attempt completion.',
                'No score creates a count or bypasses reference, confidence, visibility, range, provenance or path checks.',
                'Marker proxies and binary observability are not RGB-to-MediaPipe accuracy.',
                'Unmatched or missing reps remain in the original denominators; missing feature aggregates are imputed on training subjects only.']}
    output=DATA/'quality-head-development.json';output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(json.dumps({'baseline':result['baseline'],'candidates':{n:{k:r[k] for k in ['out_of_fold_classification_accuracy','veto_only_continuous_rep_metrics']} for n,r in candidates.items()}},indent=2))


if __name__=='__main__':main()
