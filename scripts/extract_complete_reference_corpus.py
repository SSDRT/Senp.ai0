"""Complete the fixed 16 development references; never replace the frozen source replay cohort."""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import time
from extract_exercise_quality_features import DATA,process_video


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model',choices=['full','heavy'],required=True)
    parser.add_argument('--workers',type=int,default=3)
    args=parser.parse_args()
    if not 1<=args.workers<=4:raise ValueError('Use one to four bounded extraction workers')
    manifest=json.loads((DATA/'rep-extraction-report.json').read_text())
    selected=[r for r in manifest['videos'] if r['subject'] in ['001','005'] and r['quality']=='good']
    assert len(selected)==16
    output=DATA/('complete-reference-'+args.model+'-cache');output.mkdir(exist_ok=True)
    old=DATA/('rep-world-feature-cache' if args.model=='full' else 'autonomous-heavy-cache')
    failures=[];records=[];start=time.monotonic()
    for row in selected:
        assert hashlib.sha256((DATA/'videos-v3'/row['file']).read_bytes()).hexdigest()==row['sha256']
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        jobs={pool.submit(process_video,str(DATA/'videos-v3'/r['file']),66,str(output),args.model,None):r for r in selected}
        for job in concurrent.futures.as_completed(jobs):
            row=jobs[job]
            try:
                name,count=job.result()
                with gzip.open(output/(name.removesuffix('.mp4')+'.json.gz'),'rt') as stream:video=json.load(stream)
                with gzip.open(old/(name.removesuffix('.mp4')+'.json.gz'),'rt') as stream:before=json.load(stream)
                assert video['source_video_sha256']==before['source_video_sha256'] and video['model_sha256']==before['model_sha256']
                assert video['maximum_sampled_duration_ms'] is None
                old_frames=before['frames'];prefix=video['frames'][:len(old_frames)]
                assert [f['timestamp_ms'] for f in old_frames]==[f['timestamp_ms'] for f in prefix]
                # Compare retained raw inference, excluding timing measurements.
                max_delta=max((abs(x-y) for a,b in zip(old_frames,prefix) for p,q in zip(a['landmarks'],b['landmarks']) for x,y in zip(p,q)),default=0.0)
                detections_equal=all(len(a['landmarks'])==len(b['landmarks']) for a,b in zip(old_frames,prefix))
                assert detections_equal and max_delta<=1e-6,('Changed prefix inference',name,max_delta)
                record={'video':name,'source_sha256':video['source_video_sha256'],'model_sha256':video['model_sha256'],
                    'before_observations':len(old_frames),'complete_observations':len(video['frames']),
                    'last_pts_ms':video['frames'][-1]['timestamp_ms'],'source_duration_ms':video['source_duration_ms'],
                    'prefix_max_landmark_delta':max_delta,'prefix_detections_equal':detections_equal,
                    'cache_sha256':hashlib.sha256((output/(name.removesuffix('.mp4')+'.json.gz')).read_bytes()).hexdigest()}
                assert 0<record['source_duration_ms']-record['last_pts_ms']<=100
                records.append(record);print(f'COMPLETE {args.model} {len(records)}/16 {name} {len(old_frames)} -> {len(video["frames"])}',flush=True)
            except Exception as error:failures.append({'video':row['file'],'error':repr(error)});print('FAILED',row['file'],repr(error),flush=True)
    report={'kind':'expanded-reference-evidence-not-a-new-algorithm-or-independent-accuracy-test','model':args.model,
        'maximum_sampled_duration_ms':None,'sample_period_ms':66,'new_human_labels':0,'source_replay_cohort_changed':False,
        'videos':sorted(records,key=lambda r:r['video']),'failures':failures,'seconds':time.monotonic()-start}
    (DATA/('complete-reference-'+args.model+'-extraction.json')).write_text(json.dumps(report,indent=2)+'\n')
    if failures:raise SystemExit('Complete reference extraction did not pass identity/prefix checks')


if __name__=='__main__':main()
