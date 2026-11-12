"""Fixed ABBA camera-use-case diagnostic; no short-window performance credit."""
import argparse,csv,hashlib,json,re,statistics
from pathlib import Path
from analyze_live_camera_soak import summarize,percentile95

ORDER=[True,False,False,True]


def block_analysis(summary,rows,memory,errors,model,fatal=0):
    start,end=summary['measurement_start_ns'],summary['measurement_end_ns']
    expected='UNCHANGED_ACTIVITY_PREVIEW_AND_ANALYSIS' if summary['requested_preview'] else 'REBOUND_OWNED_ANALYSIS_ONLY'
    if (type(summary['requested_preview']) is not bool or summary['actual_mode']!=expected or
        summary['warmup_ms']!=15000 or summary['requested_measurement_ms']!=45000 or
        not 45000<=(end-start)/1e6<=50000 or not summary['snapshot_saved']):
        raise ValueError('Frozen ablation binding/window changed')
    result=summarize(rows,memory,start,end,model,summary['maximum_queue_capacity'],len(errors),fatal)
    if result['readiness_points_earned'] or result['performance_pass'] or result['memory_pass']:
        raise ValueError('Short ablation cannot qualify sustained-operation gates')
    selected=[r for r in rows if start<=r['result_ns']<=end]
    dimensions=sorted({(r['width'],r['height']) for r in selected})
    intervals=[];cpu_supported=True
    for a,b in zip(selected,selected[1:]):
        cpu=b['callback_thread_cpu_ns']-a['callback_thread_cpu_ns'];wall=b['result_ns']-a['result_ns']
        valid=(a['callback_tid']==b['callback_tid'] and a['callback_thread_cpu_ns']>0 and 0<=cpu<=wall+1_000_000)
        if not valid:cpu_supported=False
        intervals.append(dict(cpu_ms=cpu/1e6 if valid else None,wall_ms=wall/1e6))
    paired=[(r['result_ns']-r['analyzer_arrival_ns']-r['inference_ns'])/1e6 for r in selected]
    if any(x<0 for x in paired):raise ValueError('Native detect cannot exceed its containing analyzer interval')
    result.update(block=summary['block'],requested_preview=summary['requested_preview'],actual_mode=summary['actual_mode'],dimensions=dimensions,callback_thread_priorities=sorted({r['callback_thread_priority'] for r in selected}),raw_status_counts={k:sum(r['raw_status']==k for r in selected) for k in sorted({r['raw_status'] for r in selected})},snapshot_export_ms=summary['snapshot_export_ns']/1e6,paired_unattributed_analyzer_p95_ms=percentile95(paired),callback_interval_cpu_supported=cpu_supported and bool(intervals),callback_interval_thread_cpu_total_ms=sum(x['cpu_ms'] for x in intervals) if cpu_supported and intervals else None,callback_interval_wall_total_ms=sum(x['wall_ms'] for x in intervals),callback_interval_thread_cpu_p95_ms=percentile95([x['cpu_ms'] for x in intervals]) if cpu_supported else None,callback_interval_wall_p95_ms=percentile95([x['wall_ms'] for x in intervals]),thread_cpu_scope='analyzer callback-to-callback: includes intervening work, excludes sleeping; not native-inference CPU attribution')
    return result


def compare_blocks(blocks):
    if len(blocks)!=4 or [b['requested_preview'] for b in blocks]!=ORDER:raise ValueError('All four ABBA blocks are required')
    same_dimensions=all(b['dimensions']==blocks[0]['dimensions'] for b in blocks)
    groups={name:dict(delivered_fps=[b['delivered_fps'] for b in blocks if b['requested_preview']==enabled],p95_native_detect_ms=[b['p95_native_detect_ms'] for b in blocks if b['requested_preview']==enabled]) for name,enabled in [('preview',True),('analysis_only',False)]}
    ratio=statistics.mean(groups['analysis_only']['delivered_fps'])/statistics.mean(groups['preview']['delivered_fps'])
    return dict(kind='short-device-specific-ABBA-diagnostic-not-camera-form-accuracy',blocks=blocks,groups=groups,analysis_only_to_preview_mean_fps_ratio=ratio,all_blocks_same_dimensions=same_dimensions,identical_source_frames_proven=False,production_optimization_promoted=False,readiness_points_earned=0,human_rep_precision=None)


def main():
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--logcat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve original ablation analysis')
    summary=json.loads((a.directory/'summary.json').read_bytes())
    if summary['schema']!='senpai-camera-preview-ablation/1' or summary['block_order']!=ORDER or not summary['real_camera_capture_measured'] or summary['generated_replay_timestamps']:raise ValueError('Fixed real-camera ablation required')
    fatal=[x for x in a.logcat.read_text(encoding='utf-8',errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',x)]
    results=[]
    for i,enabled in enumerate(ORDER):
        directory=a.directory/(f'block{i+1}-'+('preview' if enabled else 'analysis-only'))
        block=json.loads((directory/'summary.json').read_bytes());assert block==summary['blocks'][i]
        with (directory/'frames.csv').open(newline='') as f:rows=[{k:v if k in {'model_sha256','raw_status'} else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
        results.append(block_analysis(block,rows,json.loads((directory/'memory.json').read_bytes()),json.loads((directory/'errors.json').read_bytes()),summary['model_sha256'],len(fatal)))
    report=compare_blocks(results);report.update(schema='senpai-camera-preview-ablation-analysis/1',api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],fatal_log_lines=fatal,input_sha256={str(q):hashlib.sha256(q.read_bytes()).hexdigest() for q in sorted(a.directory.rglob('*')) if q.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
