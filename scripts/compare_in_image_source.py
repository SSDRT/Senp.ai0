"""Descriptive paired source consequences; never estimates human rep accuracy."""
import hashlib,json
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge'
ACCEPTED={'CORRECT','ACCEPTABLE'}


def accepted(row):
    return {(a['started_ms'],a['completed_ms'],a['verdict']):a for a in row['attempts'] if a['verdict'] in ACCEPTED}


def main():
    source=DATA/'in-image-source-screen/summary.json';destination=DATA/'in-image-source-comparison.json'
    if destination.exists():raise FileExistsError('Preserve original comparison')
    screen=json.loads(source.read_bytes());assert screen['schema']=='senpai-in-image-source-audit/3' and screen['explicit_source_reference_pipeline_pairing'] and screen['fixed_source_side_total']==96 and screen['variant_replays']==76
    pairs={};totals={v:Counter() for v in ['original','in_image']};by_label={v:Counter() for v in totals};changed=[];withdrawn=added=retained=0
    for row in screen['rows']:
        if row['status']!='REPLAYED':continue
        key=(row['video'],row['side']);pairs.setdefault(key,{})[row['variant']]=row
        totals[row['variant']].update(row['counts']);by_label[row['variant']][row['weak_whole_clip_label']]+=row['counts']['qualified']
    assert len(pairs)==38 and all(set(p)==set(totals) for p in pairs.values())
    for (video,side),pair in pairs.items():
        a,b=pair['original'],pair['in_image'];aa,bb=accepted(a),accepted(b);common=aa.keys()&bb.keys();lost=aa.keys()-bb.keys();new=bb.keys()-aa.keys()
        retained+=len(common);withdrawn+=len(lost);added+=len(new)
        if a['attempts']!=b['attempts'] or a['faults']!=b['faults'] or a['counts']!=b['counts']:
            changed.append(dict(video=video,side=side,model=a['model'],fit=a['fit'],reference_video=a['reference_video'],weak_whole_clip_label=a['weak_whole_clip_label'],original_counts=a['counts'],masked_counts=b['counts'],qualified_intervals_retained=len(common),qualified_intervals_withdrawn=[aa[k] for k in sorted(lost)],qualified_intervals_added=[bb[k] for k in sorted(new)],original_attempts=a['attempts'],masked_attempts=b['attempts'],original_faults=a['faults'],masked_faults=b['faults']))
    previous=DATA/'in-image-source-screen-v2-pipeline-mismatch'
    for p in (DATA/'in-image-source-screen').glob('*.json'):
        if not p.name.endswith('-original.json'):continue
        assert json.loads(p.read_bytes())==json.loads((previous/p.name).read_bytes()),'Pipeline correction changed a control trace'
    for p in (DATA/'in-image-source-screen').glob('*-in_image.json'):
        row=json.loads(p.read_bytes());assert all(t['unavailable_reason']!='PREPROCESSING_MISMATCH' for t in row['trace'])
    report=dict(schema='senpai-in-image-source-comparison/1',kind='unlabelled-camera-reference-imitation-engine-consequences-not-human-rep-accuracy',summary_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),fixed_source_side_total=96,reference_qualified_source_sides=38,variant_replays=76,reference_choice_from_source_outcomes=False,original_baseline_event_trace_equality=True,pipeline_correction_all_38_control_traces_equal=True,candidate_source_reference_pipeline_explicitly_paired=True,engine_output_counts={v:dict(c) for v,c in totals.items()},qualified_outputs_by_weak_whole_clip_metadata_not_rep_truth={v:dict(c) for v,c in by_label.items()},qualified_intervals_retained_exactly=retained,qualified_intervals_withdrawn_or_shifted=withdrawn,qualified_intervals_added_or_shifted=added,changed_run_count=len(changed),changed_runs=changed,production_changed=False,human_rep_precision=None,final_subjects_opened=False,readiness_points_earned=0)
    destination.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k!='changed_runs'},indent=2))


if __name__=='__main__':main()
