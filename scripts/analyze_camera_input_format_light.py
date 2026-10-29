"""Format followup with original image exports outside the declared rate windows."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

from analyze_camera_input_format import block_analysis as original_block, compare, ORDER


def block_analysis(s,rows,memory,errors,model,fatal=0):
    start=s['measurement_start_ns']
    if (s.get('snapshot_export_outside_measurement') is not True or s.get('screen_export_outside_measurement') is not True or
            not 0<s.get('snapshot_export_finished_ns',0)<start or
            not 0<s['screen_capture_ns']<=s['screen_capture_ns']+s['screen_export_ns']<start or
            s['post_bind_baseline_frames']<0 or s['post_screen_baseline_frames']<=s['post_bind_baseline_frames']):
        raise ValueError('Original exports must finish before the measured window with declared post-screen baseline')
    before=[r for r in rows if r['result_ns']<start]
    screen_end=s['screen_capture_ns']+s['screen_export_ns']
    if sum(r['result_ns']>screen_end for r in before)<3:
        raise ValueError('Three post-screen completed results required')
    result=original_block(s,rows,memory,errors,model,fatal)
    result.update(image_and_screen_exports_outside_window=True,snapshot_export_finished_ns=s['snapshot_export_finished_ns'],
                  screen_export_finished_ns=s['screen_capture_ns']+s['screen_export_ns'])
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--pixel-gate',type=Path,required=True)
    p.add_argument('--logcat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve light-observer comparison')
    summary=json.loads((a.directory/'summary.json').read_bytes())
    if summary['schema']!='senpai-camera-input-format-light-observer/1' or summary['block_order']!=ORDER or not summary['real_camera_capture_measured'] or summary['generated_replay_timestamps']:
        raise ValueError('Frozen actual-camera light-observer domain required')
    fatal=[x for x in a.logcat.read_text(encoding='utf-8-sig',errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',x)];blocks=[]
    for i,mode in enumerate(ORDER):
        folder=a.directory/(f'block{i+1}-'+mode.lower());s=json.loads((folder/'summary.json').read_bytes())
        if s!=summary['blocks'][i]:raise ValueError('Original block receipt changed')
        with (folder/'frames.csv').open(newline='') as f:
            rows=[{k:v if k in {'model_sha256','raw_status'} else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
        blocks.append(block_analysis(s,rows,json.loads((folder/'memory.json').read_bytes()),json.loads((folder/'errors.json').read_bytes()),summary['model_sha256'],len(fatal)))
    report=compare(blocks,json.loads(a.pixel_gate.read_bytes()));report['schema']='senpai-camera-input-format-light-observer-analysis/1'
    report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],fatal_log_lines=fatal,
        pixel_gate_sha256=hashlib.sha256(a.pixel_gate.read_bytes()).hexdigest(),
        input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in {'blocks','input_sha256'}},indent=2))


if __name__=='__main__':main()
