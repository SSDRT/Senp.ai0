"""Partition native-task body costs; static input is never camera/FPS/form truth."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

from profile_live_processing_stages import stats

ORDER = [False, True, True, False]
CASES = ['original-room', 'upstream-positive']
STAGES = ['owned_copy_ns','mpimage_build_ns','native_task_call_ns','mpimage_close_ns','explicit_recycle_ns']
MODEL_SHA = '5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1'
EXPECTED = {
    'original-room': ('e0486315d8c9b7237bedd305b063213750882a7f59a037f4d8e47dbc506ae8d4',480,640),
    'upstream-positive': ('cc5d2b02b7bb570a95d1cc77c5292c54b8e7990817f5fd67f9e232e5335eff18',640,427),
}


def analyze(summary, blocks):
    if (summary['schema'] != 'senpai-frozen-detect-stages/1' or summary['generated_model_timestamps'] is not True or
            summary['model_input_is_actual_camera_frame'] is not False or len(blocks) != 8 or
            summary.get('model_sha256') != MODEL_SHA or summary.get('compiler') != 'qualified-rep-compiler/9'):
        raise ValueError('Frozen native-body diagnostic domain/eight blocks required')
    results, comparisons = [], []
    for offset,case in zip([0,4],CASES):
        group = blocks[offset:offset+4]
        if [b['metadata']['camera_active'] for b in group] != ORDER:
            raise ValueError('Fixed quiet/active/active/quiet order required')
        for index,block in enumerate(group):
            meta,rows = block['metadata'],block['rows']
            if (meta['case_id'] != case or meta['block_order'] != index+1 or block['errors'] or len(rows) != 32 or
                    (meta['model_pixel_sha256'],meta['model_input_width'],meta['model_input_height']) != EXPECTED[case]):
                raise ValueError('Frozen original pixels, dimensions, row counts and identities required')
            if len({r['native_graph_identity'] for r in rows}) != 1 or len({r['owner_tid'] for r in rows}) != 1:
                raise ValueError('One graph/owner per block required')
            for i,row in enumerate(rows):
                if (row['index'] != i or row['warmup'] != (i<8) or row['generated_model_timestamp_ms'] != i*67 or
                        row['native_result_timestamp_ms'] != i*67 or row['owner_priority'] != -2):
                    raise ValueError('Warmup, generated model time, returned time or priority changed')
                if (any(type(row[k]) is not int or row[k]<0 for k in STAGES) or row['native_task_call_ns']<=0 or
                        row['partitioned_body_ns'] != sum(row[k] for k in STAGES)):
                    raise ValueError('Invalid nonnegative, complete telescoping stage partition')
                if type(row['native_pose_count']) is not int or not 0<=row['native_pose_count']<=1:
                    raise ValueError('Original one-pose native result required')
                if meta['camera_active']:
                    if type(row['camera_capture_ns']) is not int or row['camera_capture_ns']<=0:
                        raise ValueError('Separate actual camera-source provenance required')
                elif row['camera_capture_ns'] is not None or row['camera_conversion_ns'] != 0:
                    raise ValueError('Quiet static input must not acquire a camera timestamp')
            measured = rows[8:]
            owner = [r['body_owner_cpu_ns'] for r in measured]
            results.append(dict(meta,stages={k:stats([r[k] for r in measured]) for k in STAGES+['partitioned_body_ns']},
                body_owner_cpu=stats(owner) if all(type(v) is int and v>=0 for v in owner) else None,
                body_process_cpu=stats([r['body_process_cpu_ms']*1_000_000 for r in measured]),
                native_call_fraction_of_partition=sum(r['native_task_call_ns'] for r in measured)/sum(r['partitioned_body_ns'] for r in measured),
                native_pose_counts=sorted({r['native_pose_count'] for r in measured})))
        comparisons.append(dict(case_id=case,active_to_quiet_mean_ratio={k:
            statistics.mean(r['stages'][k]['mean_ms'] for r in results[-4:] if r['camera_active'])/
            statistics.mean(r['stages'][k]['mean_ms'] for r in results[-4:] if not r['camera_active'])
            if statistics.mean(r['stages'][k]['mean_ms'] for r in results[-4:] if not r['camera_active'])>0 else None
            for k in STAGES+['partitioned_body_ns']}))
    return dict(schema='senpai-frozen-detect-stages-analysis/1',blocks=results,comparisons=comparisons,
        all_rows=256,measured_rows=192,static_model_input=True,native_API_call_not_operator_only=True,
        owner_CPU_excludes_native_workers=True,process_CPU_includes_other_application_work=True,
        capture_to_result_latency=None,live_delivered_fps=None,human_rep_precision=None,
        production_optimization_promoted=False,readiness_points_earned=0)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve native-stage analysis')
    summary=json.loads((a.directory/'summary.json').read_bytes());blocks=[]
    for metadata in summary['blocks']:
        folder=a.directory/metadata['folder']
        blocks.append(dict(metadata=metadata,rows=json.loads((folder/'rows.json').read_bytes()),errors=json.loads((folder/'errors.json').read_bytes())))
    report=analyze(summary,blocks)
    report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],
                  input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in {'blocks','input_sha256'}},indent=2))


if __name__=='__main__':main()
