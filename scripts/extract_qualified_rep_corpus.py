"""Extract an explicit development/replay subset at >=66 ms source-PTS spacing.

Whole-clip quality labels remain weak metadata. These clips are not expert
rep/fault labels or a fresh held-out accuracy test. No source frames are invented.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import time
from extract_exercise_quality_features import DATA,MODEL,MODEL_SHA,FILENAME,process_video

def main():
    if hashlib.sha256(MODEL.read_bytes()).hexdigest()!=MODEL_SHA:raise ValueError('Pose model mismatch')
    destination=DATA/'rep-world-feature-cache';destination.mkdir(exist_ok=True)
    manifest=json.loads((DATA/'corpus-video-manifest.json').read_text())
    selected=[row for row in manifest['videos'] if row['subject'] in ['001','005','018'] and
              row['exercise'] in ['squat','push_up','bicep_curl','shoulder'] and row['view'] in ['front','side']]
    if len(selected)!=48:raise ValueError('Unexpected explicit replay selection')
    for row in selected:
        path=DATA/'videos-v3'/row['file']
        if hashlib.sha256(path.read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Video integrity mismatch')
    start=time.monotonic();completed=[];failures=[]
    with concurrent.futures.ProcessPoolExecutor(max_workers=3) as pool:
        futures={pool.submit(process_video,str(DATA/'videos-v3'/row['file']),66,str(destination)):row for row in selected}
        for future in concurrent.futures.as_completed(futures):
            row=futures[future]
            try:
                name,count=future.result();completed.append(row)
                print(f'REP EXTRACT {len(completed)}/48 {name} observations={count}',flush=True)
            except Exception as error:failures.append({'video':row['file'],'error':repr(error)})
    report={'kind':'high-cadence-development-replay-not-independent-expert-accuracy','dataset_doi':'10.17632/kgbb3yn47p.3',
            'sample_period_ms':66,'maximum_sampled_duration_ms':24000,'pose_model_sha256':MODEL_SHA,
            'videos':sorted(completed,key=lambda r:r['file']),'failures':failures,'seconds':time.monotonic()-start,
            'limits':['Whole-clip good/bad labels are not rep/fault-time annotations.','Subjects 001/005/018 are already seen; development/regression only.']}
    (DATA/'rep-extraction-report.json').write_text(json.dumps(report,indent=2)+'\n')
    if failures:raise SystemExit('Rep extraction incomplete')

if __name__=='__main__':main()
