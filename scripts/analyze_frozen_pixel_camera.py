"""Exact-input capacity comparison, never camera-input pose or human-form accuracy."""
import argparse,hashlib,json,statistics
from pathlib import Path
from profile_live_processing_stages import stats

ORDER=[False,True,True,False]


def analyze(summary,blocks):
    if (summary['schema']!='senpai-frozen-pixel-camera/1' or not summary['generated_model_timestamps'] or
        summary['model_input_is_actual_camera_frame'] or len(blocks)!=8):raise ValueError('Fixed static-pixel domain and eight blocks required')
    results=[];comparisons=[]
    for offset in [0,4]:
        group=blocks[offset:offset+4]
        if [x['metadata']['camera_active'] for x in group]!=ORDER:raise ValueError('Frozen per-input ABBA order changed')
        first=group[0]['metadata'];pixel_hash=first['model_pixel_sha256']
        for index,block in enumerate(group):
            meta,rows=block['metadata'],block['rows']
            if (meta['block_order']!=index+1 or meta['case_id']!=first['case_id'] or meta['model_pixel_sha256']!=pixel_hash or
                (meta['model_input_width'],meta['model_input_height'])!=(first['model_input_width'],first['model_input_height']) or len(rows)!=32 or block['errors']):
                raise ValueError('Inputs, counts, dimensions or error-free sampling changed')
            for i,row in enumerate(rows):
                if row['index']!=i or row['warmup']!=(i<8) or row['generated_model_timestamp_ms']!=i*67:raise ValueError('Frozen model timebase/warmup changed')
                if (row['native_detect_ns']<=0 or row['estimate_call_ns']<row['native_detect_ns'] or row['owner_priority']!=-2 or
                    (meta['camera_active'] and (row['camera_capture_ns'] is None or row['camera_capture_ns']<=0)) or
                    (not meta['camera_active'] and (row['camera_capture_ns'] is not None or row['camera_conversion_ns']!=0))):
                    raise ValueError('Invalid nested timing, owner or separate capture provenance')
            measured=rows[8:];owner=[r['estimate_owner_cpu_ns'] for r in measured]
            record=dict(meta,native_detect=stats([r['native_detect_ns'] for r in measured]),estimate_call=stats([r['estimate_call_ns'] for r in measured]),camera_conversion=stats([r['camera_conversion_ns'] for r in measured]),estimate_owner_cpu=stats(owner) if all(type(v) is int and v>=0 for v in owner) else None,estimate_process_cpu=stats([r['estimate_process_cpu_ms']*1_000_000 for r in measured]),raw_status_counts={s:sum(r['raw_status']==s for r in measured) for s in sorted({r['raw_status'] for r in measured})},native_recreation_increase=measured[-1]['native_recreations']-measured[0]['native_recreations'],thread_ids=sorted({r['owner_tid'] for r in rows}))
            results.append(record)
        current=results[-4:];quiet=[r['native_detect']['mean_ms'] for r in current if not r['camera_active']];active=[r['native_detect']['mean_ms'] for r in current if r['camera_active']]
        comparisons.append(dict(case_id=first['case_id'],same_prepared_pixel_sha256=pixel_hash,quiet_native_mean_ms=quiet,active_native_mean_ms=active,active_to_quiet_native_mean_ratio=statistics.mean(active)/statistics.mean(quiet)))
    return dict(schema='senpai-frozen-pixel-camera-analysis/1',kind='static-model-input-capacity-under-camera-load-not-live-camera-accuracy',blocks=results,comparisons=comparisons,all_rows=256,measured_rows=192,model_input_is_actual_camera_frame=False,generated_model_timestamps=True,capture_to_result_latency=None,live_delivered_fps=None,readiness_points_earned=0,human_rep_precision=None,production_optimization_promoted=False,owner_CPU_excludes_native_workers=True,process_CPU_includes_other_application_work=True)


def main():
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve original exact-input analysis')
    summary=json.loads((a.directory/'summary.json').read_bytes());blocks=[]
    for metadata in summary['blocks']:
        folder=a.directory/metadata['folder'];blocks.append(dict(metadata=metadata,rows=json.loads((folder/'rows.json').read_bytes()),errors=json.loads((folder/'errors.json').read_bytes())))
    report=analyze(summary,blocks);report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n');print(json.dumps({k:v for k,v in report.items() if k not in {'blocks','input_sha256'}},indent=2))


if __name__=='__main__':main()
