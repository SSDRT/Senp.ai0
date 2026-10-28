"""Actual-camera preview implementation comparison; unknown clocks remain unknown."""
import argparse,csv,hashlib,json,re,statistics
from pathlib import Path
from analyze_live_camera_soak import summarize,percentile95

ORDER=['COMPATIBLE','PERFORMANCE','PERFORMANCE','COMPATIBLE']


def view_kind(names):
    kinds={n.rsplit('.',1)[-1] for n in names}&{'SurfaceView','TextureView'}
    return next(iter(kinds)) if len(kinds)==1 else 'MIXED_OR_NOT_OBSERVED'


def block_analysis(s,rows,memory,errors,model,fatal=0):
    mode='COMPATIBLE' if s['compatible_mode'] else 'PERFORMANCE';start,end=s['measurement_start_ns'],s['measurement_end_ns']
    if (type(s['compatible_mode']) is not bool or s['requested_implementation']!=mode or s['actual_mode']!='REBOUND_PREVIEW_AND_ANALYSIS' or
        s['warmup_ms']!=15000 or s['requested_measurement_ms']!=45000 or not 45000<=(end-start)/1e6<=50000 or not s['snapshot_saved']):raise ValueError('Frozen mode binding/window changed')
    result=summarize(rows,memory,start,end,model,s['maximum_queue_capacity'],len(errors),fatal)
    if result['readiness_points_earned'] or result['performance_pass'] or result['memory_pass']:raise ValueError('Short mode test cannot qualify ten-minute operation')
    selected=[r for r in rows if start<=r['result_ns']<=end]
    residual=[r['result_ns']-r['analyzer_arrival_ns']-r['inference_ns'] for r in selected]
    if any(v<0 for v in residual):raise ValueError('Native interval exceeds containing analyzer time')
    result.update(block=s['block'],requested_implementation=mode,actual_view_start=view_kind(s['preview_views_start']),actual_view_end=view_kind(s['preview_views_end']),preview_views_start=s['preview_views_start'],preview_views_end=s['preview_views_end'],dimensions=sorted({(r['width'],r['height']) for r in selected}),raw_status_counts={k:sum(r['raw_status']==k for r in selected) for k in sorted({r['raw_status'] for r in selected})},paired_unattributed_analyzer_p95_ms=percentile95(residual)/1e6,snapshot_export_ms=s['snapshot_export_ns']/1e6,screen_capture_ns=s['screen_capture_ns'],screen_export_ms=s['screen_export_ns']/1e6,screen_saved=s['screen_saved'])
    return result


def compare(blocks):
    if len(blocks)!=4 or [b['requested_implementation'] for b in blocks]!=ORDER:raise ValueError('Four fixed implementation-mode ABBA blocks required')
    groups={mode:[b['delivered_fps'] for b in blocks if b['requested_implementation']==mode] for mode in ['COMPATIBLE','PERFORMANCE']}
    observed=all(b['actual_view_start']==b['actual_view_end']==('TextureView' if b['requested_implementation']=='COMPATIBLE' else 'SurfaceView') for b in blocks)
    return dict(schema='senpai-camera-preview-mode-analysis/1',kind='short-actual-camera-implementation-diagnostic-not-human-form-accuracy',blocks=blocks,groups=groups,performance_to_compatible_mean_fps_ratio=statistics.mean(groups['PERFORMANCE'])/statistics.mean(groups['COMPATIBLE']),actual_surface_contrast_observed=observed,all_blocks_same_dimensions=all(b['dimensions']==blocks[0]['dimensions'] for b in blocks),same_source_pixels_proven=False,production_optimization_promoted=False,readiness_points_earned=0,human_rep_precision=None)


def main():
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--logcat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve mode comparison')
    summary=json.loads((a.directory/'summary.json').read_bytes())
    if summary['schema']!='senpai-camera-preview-mode/1' or summary['block_order']!=ORDER or not summary['real_camera_capture_measured'] or summary['generated_replay_timestamps']:raise ValueError('Fixed actual-camera mode comparison required')
    fatal=[x for x in a.logcat.read_text(encoding='utf-8',errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',x)];blocks=[]
    for i,mode in enumerate(ORDER):
        folder=a.directory/(f'block{i+1}-'+mode.lower());s=json.loads((folder/'summary.json').read_bytes());assert s==summary['blocks'][i]
        with (folder/'frames.csv').open(newline='') as f:rows=[{k:v if k in {'model_sha256','raw_status'} else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
        blocks.append(block_analysis(s,rows,json.loads((folder/'memory.json').read_bytes()),json.loads((folder/'errors.json').read_bytes()),summary['model_sha256'],len(fatal)))
    report=compare(blocks);report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],fatal_log_lines=fatal,input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n');print(json.dumps({k:v for k,v in report.items() if k!='input_sha256'},indent=2))


if __name__=='__main__':main()
