"""Extract pinned Heavy on the same development videos without any new manual labels."""
import concurrent.futures
import gzip
import hashlib
import json
import time
from extract_exercise_quality_features import DATA,POSE_MODELS,process_video

def main():
    model,expected=POSE_MODELS['heavy']
    if hashlib.sha256(model.read_bytes()).hexdigest()!=expected:raise ValueError('Heavy identity mismatch')
    baseline=json.loads((DATA/'rep-extraction-report.json').read_text())
    selected=baseline['videos']
    if len(selected)!=48:raise ValueError('Expected the fixed 48-video development selection')
    for row in selected:
        if hashlib.sha256((DATA/'videos-v3'/row['file']).read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Source video mismatch')
    output=DATA/'autonomous-heavy-cache';output.mkdir(exist_ok=True)
    failures=[];completed=[];start=time.monotonic()
    with concurrent.futures.ProcessPoolExecutor(max_workers=3) as pool:
        futures={pool.submit(process_video,str(DATA/'videos-v3'/r['file']),66,str(output),'heavy'):r for r in selected}
        for job in concurrent.futures.as_completed(futures):
            row=futures[job]
            try:
                name,count=job.result();completed.append(row)
                print(f'HEAVY {len(completed)}/48 {name} observations={count}',flush=True)
            except Exception as error:failures.append({'video':row['file'],'error':repr(error)})
    observations=0;timings=[]
    for row in completed:
        with gzip.open(output/(row['file'].removesuffix('.mp4')+'.json.gz'),'rt') as stream:video=json.load(stream)
        with gzip.open(DATA/'rep-world-feature-cache'/(row['file'].removesuffix('.mp4')+'.json.gz'),'rt') as stream:old=json.load(stream)
        if [f['timestamp_ms'] for f in video['frames']]!=[f['timestamp_ms'] for f in old['frames']]:raise ValueError('Model comparisons need identical original source PTS')
        observations+=len(video['frames']);timings.extend(f['research_inference_ms'] for f in video['frames'])
    timings.sort()
    report={'kind':'autonomous-model-ablation-not-human-form-accuracy','model_variant':'heavy','model_sha256':expected,
            'dataset_doi':baseline['dataset_doi'],'license':'CC BY 4.0','videos':sorted(completed,key=lambda r:r['file']),
            'failures':failures,'observations':observations,'same_source_pts_verified':True,'sample_period_ms':66,
            'seconds':time.monotonic()-start,'research_inference_ms':{'median':timings[len(timings)//2] if timings else None,
            'p95':timings[min(len(timings)-1,int(len(timings)*.95))] if timings else None},
            'release_qualified':False,'new_manual_labels':0,
            'limits':['Same three already-seen development participants.','Concurrent desktop CPU inference timings are not phone or end-to-end latency.',
                      'Model confidence and self-replay cannot establish independent rep precision.']}
    (DATA/'autonomous-heavy-extraction-report.json').write_text(json.dumps(report,indent=2)+'\n')
    if failures:raise SystemExit('Heavy extraction incomplete')

if __name__=='__main__':main()
