"""Frozen paired development screen; an unchanged denominator and no final access."""
import json
from collections import Counter
from fetch_uiprmd import checksum
from prepare_uiprmd_frontal_arm import OUT,verify_protocol
from evaluate_uiprmd_development import evaluate_rows


def development_gate(metrics):
    return (metrics['counts']['total_reps']==160 and metrics['counts']['correct_reps']==80 and
            metrics['counts']['incorrect_reps']==80 and metrics['precision'] is not None and
            metrics['precision']>=.90 and metrics['good_rep_recall']>=.70 and metrics['assessable_coverage']>=.80)


def main():
    spec,_=verify_protocol();output=OUT/'comparison.json'
    if output.exists():raise FileExistsError('Preserve original paired evaluation')
    reports={};raw={}
    for mode in ['baseline','frontal_arm']:
        path=OUT/(mode+'-replay.json');replay=json.loads(path.read_bytes())
        assert replay['schema']=='uiprmd-frontal-arm-replay/1' and replay['mode']==mode
        assert {r['subject'] for r in replay['rows']}==set(spec['subjects'])
        assert len(replay['rows'])==16
        raw[mode]=replay
        metrics=evaluate_rows(replay['rows']);assert metrics['counts']['total_reps']==160
        metrics['per_movement']={m:{k:v for k,v in evaluate_rows([r for r in replay['rows'] if r['movement']==m]).items() if k!='rep_results'} for m in ['m01','m07']}
        metrics['reference_unavailable']=dict(Counter(r.get('failure') for r in replay['rows'] if r['status']=='REFERENCE_UNAVAILABLE'))
        metrics['replay_sha256']=checksum(path,'sha256');reports[mode]=metrics
    original={ (r['subject'],r['movement'],r['correct']):r for r in raw['baseline']['rows'] }
    for r in raw['frontal_arm']['rows']:
        b=original[r['subject'],r['movement'],r['correct']];assert r['episodes']==b['episodes']
        if r['movement']=='m01':
            assert r['attempts']==b['attempts'] and r['status']==b['status']
    changes=[]
    for old,new in zip(reports['baseline']['rep_results'],reports['frontal_arm']['rep_results']):
        for key in ['subject','movement','correct','episode']:assert old[key]==new[key]
        if old['verdict']!=new['verdict']:
            changes.append(dict(subject=old['subject'],movement=old['movement'],correct=old['correct'],episode=old['episode'],baseline=old['verdict'],candidate=new['verdict']))
    passed=development_gate(reports['frontal_arm'])
    ceiling=reports['frontal_arm']['good_rep_recall']>=.70 and reports['frontal_arm']['assessable_coverage']>=.80
    report=dict(schema='uiprmd-frontal-arm-development-comparison/1',kind='development-skeletal-screen-not-camera-accuracy',development_gate_met=passed,recall_coverage_ceiling_met=ceiling,projection_discards_normal_component=True,out_of_plane_form_policy_completed=False,decision='CEILING_PASSED_REQUIRES_SEPARATELY_FROZEN_FORM_POLICY' if ceiling else 'STOP_BEFORE_QUALITY_HEAD_OR_VALIDATION',reports=reports,changed_episode_verdicts=changes,validation_accessed=False,final_test_accessed=False,production_changed=False,new_human_labels=0,readiness_points_earned=0,human_rep_precision=None)
    output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(decision=report['decision'],changed_episode_verdicts=len(changes),metrics={k:{f:v[f] for f in ['counts','precision','good_rep_recall','assessable_coverage','reference_unavailable']} for k,v in reports.items()}),indent=2))


if __name__=='__main__':main()
