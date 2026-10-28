"""Short actual-CameraX diagnostics, explicitly ineligible for ten-minute credit."""
import argparse,csv,hashlib,json,re
from pathlib import Path
from analyze_live_camera_soak import summarize
from profile_live_processing_stages import original_soak


def analyze(summary,rows,memory,errors,fatal_count=0):
    if (summary['schema']!='senpai-live-camera-probe/1' or not summary['real_camera_capture_measured'] or summary['generated_replay_timestamps'] or
        summary['requested_measurement_ms']!=60000 or summary['warmup_ms']!=30000 or summary['backpressure_strategy']!=0 or
        summary['frames_after_resume']<summary['resume_baseline_frames']+3):raise ValueError('Frozen camera-probe identity/lifecycle changed')
    start,end=summary['measurement_start_ns'],summary['measurement_end_ns'];duration=(end-start)/1e6
    if not 60000<=duration<=65000:raise ValueError('Not the declared short diagnostic window')
    result=summarize(rows,memory,start,end,summary['model_sha256'],summary['maximum_queue_capacity'],len(errors),fatal_count)
    paired=original_soak(summary,rows);paired['kind']='paired-current-camera-duration-diagnostics'
    if result['readiness_points_earned']!=0 or result['performance_pass'] or result['memory_pass']:raise ValueError('Short probe cannot qualify ten-minute performance')
    result.update(schema='senpai-live-camera-probe-analysis/1',kind='short-current-CameraX-runtime-diagnostics-not-human-form-accuracy-or-sustained-qualification',api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],real_camera_capture_measured=True,generated_replay_timestamps=False,ten_minute_window=False,paired_processing=paired,human_rep_precision=None,new_human_labels=0)
    return result


def main():
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--logcat',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve camera diagnostic analysis')
    summary=json.loads((a.directory/'summary.json').read_bytes());memory=json.loads((a.directory/'memory.json').read_bytes());errors=json.loads((a.directory/'errors.json').read_bytes())
    with (a.directory/'frames.csv').open(newline='') as f:rows=[{k:v if k=='model_sha256' else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
    fatal=[x for x in a.logcat.read_text(encoding='utf-8',errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',x)]
    result=analyze(summary,rows,memory,errors,len(fatal));result['fatal_log_lines']=fatal
    inputs=[a.directory/n for n in ['summary.json','frames.csv','memory.json','errors.json']]+[a.logcat]
    result['input_sha256']={str(q):hashlib.sha256(q.read_bytes()).hexdigest() for q in inputs};a.output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n');print(json.dumps(result,indent=2))


if __name__=='__main__':main()
