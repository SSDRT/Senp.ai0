"""Actual live-analyzer format ABBA after a separately frozen pixel-fidelity gate."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import statistics

from analyze_live_camera_soak import summarize, percentile95
from analyze_camera_preview_mode import view_kind

ORDER=['RGBA','YUV','YUV','RGBA']
MODEL='5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1'


def block_analysis(s, rows, memory, errors, model, fatal=0):
    mode='RGBA' if s['rgba_mode'] else 'YUV';start,end=s['measurement_start_ns'],s['measurement_end_ns']
    if (type(s['rgba_mode']) is not bool or s['requested_input_format']!=mode or s['actual_mode']!='REBOUND_FORMAT_PREVIEW_AND_ANALYSIS' or
            s['warmup_ms']!=15000 or s['requested_measurement_ms']!=45000 or not 45000<=(end-start)/1e6<=50000 or
            not s['snapshot_saved'] or model!=MODEL):raise ValueError('Frozen actual-format binding/window/model required')
    result=summarize(rows,memory,start,end,model,s['maximum_queue_capacity'],len(errors),fatal)
    selected=[r for r in rows if start<=r['result_ns']<=end]
    expected=1 if mode=='RGBA' else 35
    for r in selected:
        if (r['input_format']!=expected or (r['input_width'],r['input_height'],r['input_rotation'])!=(640,480,90) or
                (r['width'],r['height'])!=(480,640) or r['input_full_crop']!=1 or r['input_one_pixel_shift']!=0):
            raise ValueError('Measured input does not match the prior bit-exact scope')
        layout=[(r['input_row'+str(n)],r['input_pixel'+str(n)]) for n in range(3)]
        if mode=='YUV' and (r['input_planes']!=3 or layout!=[(640,1),(320,1),(320,1)]):raise ValueError('Untested YUV plane layout')
        if mode=='RGBA' and (r['input_planes']!=1 or layout[0]!=(2560,4)):raise ValueError('Untested RGBA plane layout')
        if r['callback_thread_priority']!=-2:raise ValueError('Analyzer owner priority changed')
    residual=[r['result_ns']-r['analyzer_arrival_ns']-r['inference_ns'] for r in selected]
    if any(v<0 for v in residual):raise ValueError('Native interval exceeds its containing analyzer call')
    if result['readiness_points_earned'] or result['performance_pass'] or result['memory_pass']:raise ValueError('Short format test earns no soak credit')
    result.update(block=s['block'],requested_input_format=mode,observed_source_formats=sorted({r['input_format'] for r in selected}),
        preview_view_start=view_kind(s['preview_views_start']),preview_view_end=view_kind(s['preview_views_end']),
        paired_unattributed_analyzer_p95_ms=percentile95(residual)/1e6,
        snapshot_export_ms=s['snapshot_export_ns']/1e6,screen_export_ms=s['screen_export_ns']/1e6,
        raw_status_counts={v:sum(r['raw_status']==v for r in selected) for v in sorted({r['raw_status'] for r in selected})})
    return result


def compare(blocks, pixel_gate):
    if (len(blocks)!=4 or [b['requested_input_format'] for b in blocks]!=ORDER or
            pixel_gate.get('pixel_fidelity_gate_pass') is not True or pixel_gate.get('pairs')!=6):
        raise ValueError('Frozen four-block order and completed prior fidelity gate required')
    if any(b['preview_view_start']!=b['preview_view_end'] or b['preview_view_start']!=blocks[0]['preview_view_start'] or
           b['preview_view_start'] not in {'SurfaceView','TextureView'} for b in blocks):raise ValueError('Preview implementation changed or was not observed')
    groups={m:[b['delivered_fps'] for b in blocks if b['requested_input_format']==m] for m in ['RGBA','YUV']}
    return dict(schema='senpai-camera-input-format-analysis/1',blocks=blocks,groups=groups,
        yuv_to_rgba_mean_fps_ratio=statistics.mean(groups['YUV'])/statistics.mean(groups['RGBA']),
        prior_same_frame_pixel_gate_passed=True,same_source_pixels_across_camera_blocks_proven=False,
        production_optimization_promoted=False,readiness_points_earned=0,human_rep_precision=None)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--pixel-gate',type=Path,required=True)
    p.add_argument('--logcat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve original format comparison')
    summary=json.loads((a.directory/'summary.json').read_bytes())
    if summary['schema']!='senpai-camera-input-format/1' or summary['block_order']!=ORDER or not summary['real_camera_capture_measured'] or summary['generated_replay_timestamps']:
        raise ValueError('Fixed actual-camera input-format domain required')
    fatal=[x for x in a.logcat.read_text(encoding='utf-8-sig',errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',x)]
    blocks=[]
    for i,mode in enumerate(ORDER):
        folder=a.directory/(f'block{i+1}-'+mode.lower());s=json.loads((folder/'summary.json').read_bytes())
        if s!=summary['blocks'][i]:raise ValueError('Original block receipt changed')
        with (folder/'frames.csv').open(newline='') as f:
            rows=[{k:v if k in {'model_sha256','raw_status'} else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
        blocks.append(block_analysis(s,rows,json.loads((folder/'memory.json').read_bytes()),json.loads((folder/'errors.json').read_bytes()),summary['model_sha256'],len(fatal)))
    report=compare(blocks,json.loads(a.pixel_gate.read_bytes()))
    report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],fatal_log_lines=fatal,
        pixel_gate_sha256=hashlib.sha256(a.pixel_gate.read_bytes()).hexdigest(),
        input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in {'blocks','input_sha256'}},indent=2))


if __name__=='__main__':main()
