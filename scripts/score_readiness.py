"""Fixed engineering-readiness score, never an estimate of human-form accuracy."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def independent_qualifies(report,measurement_domain):
    if not report:return False
    if report.get('measurement_domain')!=measurement_domain:return False
    split=report.get('subjects',{})
    train=set(split.get('development',[]));validation=set(split.get('validation',[]));test=set(split.get('test',[]))
    if not train or not validation or len(test)<3:return False
    if train&validation or train&test or validation&test:return False
    probabilities=[report.get(k,0) for k in ['precision','good_rep_recall','assessable_coverage']]
    if not all(type(v) in (int,float) and math.isfinite(v) and 0<=v<=1 for v in probabilities):return False
    return (report.get('existing_independent_labels') is True and report.get('frozen_before_test') is True and
        len(set(report.get('exercise_ids',[])))>=2 and report.get('correct_test_reps',0)>=30 and
        report.get('incorrect_test_reps',0)>=30 and report.get('precision',0)>=.90 and
        report.get('good_rep_recall',0)>=.70 and report.get('assessable_coverage',0)>=.80)


def score(metrics,rubric):
    expected_policies={'bodyweight_squat','biceps_curl','push_up','overhead_press','glute_bridge','lateral_raise'}
    if rubric['target']!=70 or set(rubric['policy_ids'])!=expected_policies:raise ValueError('Frozen target or policy set changed')
    if set(rubric['weights'])!={'causal_mechanism_regressions','frozen_real_reference_coverage','real_camera_exercise_breadth',
        'independent_label_validation','android_runtime_validation','sustained_live_performance'}:
        raise ValueError('Unrecognized rubric categories')
    if rubric['weights']!={'causal_mechanism_regressions':20,'frozen_real_reference_coverage':20,'real_camera_exercise_breadth':15,
        'independent_label_validation':20,'android_runtime_validation':15,'sustained_live_performance':10}:
        raise ValueError('Frozen weights changed')
    if metrics['reference_total']!=32:raise ValueError('Frozen reference denominator changed')
    usable=metrics['reference_usable']
    if type(usable) is not int or not 0<=usable<=32:raise ValueError('Invalid reference count')
    exercises=set(metrics.get('camera_exercise_ids',[]))
    if not exercises<=set(rubric['policy_ids']):raise ValueError('Unsupported policy in breadth metric')
    skeleton=independent_qualifies(metrics.get('independent_skeleton'),'skeletal')
    camera=independent_qualifies(metrics.get('independent_camera'),'rgb-to-pose')
    runtimes=metrics.get('android_runtimes',[])
    emulator_apis={r['api'] for r in runtimes if r.get('emulator') is True and r.get('all_tests_pass') is True}
    physical=any(r.get('emulator') is False and r.get('all_tests_pass') is True for r in runtimes)
    soak=metrics.get('live_soak') or {}
    finite_soak=all(type(soak.get(k)) in (int,float) and math.isfinite(soak[k]) and soak[k]>=0
        for k in ['duration_ms','p95_capture_to_result_ms','delivered_fps','maximum_queue'])
    soak_pass=(finite_soak and soak.get('duration_ms',0)>=600000 and soak.get('p95_capture_to_result_ms',float('inf'))<=200 and
        soak.get('delivered_fps',0)>=5 and soak.get('maximum_queue',float('inf'))<=1 and
        soak.get('model_mismatch_count',-1)==0 and soak.get('crash_or_anr_count',-1)==0)
    finite_memory=all(type(soak.get(k)) in (int,float) and math.isfinite(soak[k]) and soak[k]>0
        for k in ['maximum_pss_mib','post_warmup_windows_ms','first_window_pss_mib','last_window_pss_mib'])
    memory_pass=(soak_pass and finite_memory and soak.get('maximum_pss_mib',float('inf'))<=768 and
        soak.get('post_warmup_windows_ms',0)>=120000 and 0<soak.get('first_window_pss_mib',0) and
        soak.get('last_window_pss_mib',float('inf'))<soak['first_window_pss_mib']*1.10)
    points={
        'causal_mechanism_regressions':20 if metrics.get('mechanism_and_scoped_tests_pass') is True else 0,
        'frozen_real_reference_coverage':20*usable/32,
        'real_camera_exercise_breadth':15*len(exercises)/6,
        'independent_label_validation':10*int(skeleton)+10*int(camera),
        'android_runtime_validation':5*min(2,len(emulator_apis))+5*int(physical),
        'sustained_live_performance':5*int(soak_pass)+5*int(memory_pass),
    }
    raw=sum(points.values())
    reported=raw if skeleton or camera else min(69,raw)
    milestone=(reported>=rubric['target'] and (skeleton or camera) and metrics.get('mechanism_and_scoped_tests_pass') is True and
        metrics.get('undocumented_reference_losses',0)==0 and metrics.get('verified_engine_commit') is not None)
    return {'schema':'senpai-readiness-score/1','kind':'engineering-readiness-not-human-form-accuracy',
        'points':points,'raw_score':raw,'reported_score':reported,'target':rubric['target'],'milestone_reached':milestone,
        'actual_live_rep_precision':None,'verified_engine_commit':metrics.get('verified_engine_commit')}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=Path,default=ROOT/'models/readiness-baseline.evidence.json')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    evidence=json.loads(args.evidence.read_text())
    rubric_path=ROOT/'models/readiness-rubric.json'
    if hashlib.sha256(rubric_path.read_bytes()).hexdigest()!=evidence['rubric_sha256']:raise ValueError('Rubric receipt does not match')
    for proof in evidence['proofs']:
        path=ROOT/proof['path']
        if hashlib.sha256(path.read_bytes()).hexdigest()!=proof['sha256']:raise ValueError(f'Evidence changed: {path}')
    result=score(evidence['metrics'],json.loads(rubric_path.read_text()))
    result['evidence_sha256']=hashlib.sha256(args.evidence.read_bytes()).hexdigest()
    result['rubric_sha256']=evidence['rubric_sha256']
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
