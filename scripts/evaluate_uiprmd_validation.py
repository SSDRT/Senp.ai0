"""Predict with the frozen development fit; never fit validation or open final test."""
import hashlib
import json
from contextlib import ExitStack
import zipfile
import joblib
import numpy as np
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import parse_publisher_array
from prepare_uiprmd_validation import frozen_candidate, read_validation_member, PROTOCOL, SUBJECTS
from uiprmd_quality_features import SCHEMA, frame_features, aggregate
from train_uiprmd_quality_heads import evaluate_veto


def main():
    protocol = frozen_candidate()
    replay_path = DATA/'validation-replay.json'
    replay = json.loads(replay_path.read_text())
    if (replay['schema'] != 'uiprmd-validation-replay/1' or replay['compiler'] != protocol['compiler'] or
        replay['candidate_protocol_sha256'] != checksum(PROTOCOL, 'sha256') or
        replay['final_test_trajectories_opened'] or SCHEMA != protocol['feature_schema']):
        raise ValueError('Validation replay or candidate changed')
    if checksum(DATA/'validation-vicon/manifest.json', 'sha256') != replay['manifest_sha256']:
        raise ValueError('Validation manifest changed')
    expected = {(s,m,c) for s in SUBJECTS for m in ['m01','m07'] for c in [True,False]}
    observed = [(r['subject'],r['movement'],r['correct']) for r in replay['rows']]
    if len(observed) != len(expected) or set(observed) != expected:
        raise ValueError('Incomplete or duplicate validation cohort')
    if any(r['reference_subject'] != '01' for r in replay['rows']):
        raise ValueError('Reference must remain the frozen development subject')
    classifiers = {m:joblib.load(ROOT/next(a['path'] for a in protocol['fitted_models']
                                        if a['path'].endswith('/'+m+'-logistic.joblib'))) for m in ['m01','m07']}
    scores, samples, reads = [], [], []
    with ExitStack() as stack:
        archives = {n:stack.enter_context(zipfile.ZipFile(DATA/n)) for n in ['Movements.zip','Incorrect Movements.zip']}
        for i,row in enumerate(replay['rows']):
            archive_name = 'Movements.zip' if row['correct'] else 'Incorrect Movements.zip'
            raw = read_validation_member(archives[archive_name],row['source_member'])
            digest = hashlib.sha256(raw).hexdigest()
            if digest != row['source_sha256']:raise ValueError('Validation source changed')
            reads.append({'archive':archive_name,'member':row['source_member'],'sha256':digest})
            array,_ = parse_publisher_array(raw,'Vicon')
            values = frame_features(array)
            # Inference selection is label blind: score every emitted attempt,
            # including unmatched events, with no episode boundaries as inputs.
            for j,attempt in enumerate(row['attempts']):
                first = int(np.ceil(attempt['started_ms']/10))
                last = min(len(array)-1,int(np.floor(attempt['completed_ms']/10)))
                vector,names = aggregate(values,first,last)
                if names != protocol['feature_names']:raise ValueError('Feature order changed')
                classifier = classifiers[row['movement']]
                score = classifier.predict_proba([vector])[0,list(classifier.classes_).index(1)]
                samples.append({'row':i,'attempt':j});scores.append(float(score))
    baseline = evaluate_veto(replay,[],[],protocol['veto_bad_score_threshold'])
    candidate = evaluate_veto(replay,samples,scores,protocol['veto_bad_score_threshold'])
    if any(result['counts']['total_reps'] != 120 or result['counts']['correct_reps'] != 60 or
           result['counts']['incorrect_reps'] != 60 for result in [baseline,candidate]):
        raise ValueError('Frozen validation denominators changed')
    criteria = protocol['evaluation']
    passes = (candidate['precision'] is not None and candidate['precision'] >= criteria['counted_precision_minimum'] and
              candidate['good_rep_recall'] >= criteria['good_rep_recall_minimum'] and
              candidate['assessable_coverage'] >= criteria['assessable_coverage_minimum'])
    result = {'schema':'uiprmd-quality-validation/1','kind':'independent-skeletal-validation-not-final-test-or-live-camera-accuracy',
              'candidate_protocol_sha256':checksum(PROTOCOL,'sha256'),'replay_sha256':checksum(replay_path,'sha256'),
              'baseline':baseline,'candidate':candidate,'passes_frozen_validation_criteria':passes,
              'validation_subjects':sorted(SUBJECTS),'existing_labels':True,'all_original_reps_retained':True,
              'refitted_on_validation':False,'final_test_subjects_opened':False,'promotion_approved':False,
              'readiness_points_earned':0,'production_changed':False,'reads':reads,
              'scores':[dict(sample,bad_score_uncalibrated=score) for sample,score in zip(samples,scores)],
              'fitted_models':protocol['fitted_models'],
              'source_code_sha256':{p:checksum(ROOT/p,'sha256') for p in ['scripts/prepare_uiprmd_validation.py',
                  'scripts/evaluate_uiprmd_validation.py','core-motion/src/test/kotlin/ai/senp/motion/UiprmdDevelopmentReplay.kt']}}
    output = DATA/'quality-head-validation.json'
    output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(json.dumps({k:result[k] for k in ['baseline','candidate','passes_frozen_validation_criteria']},indent=2))


if __name__ == '__main__':main()
