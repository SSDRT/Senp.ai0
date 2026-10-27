"""Research-only Lite VIDEO reference screen with original MP33/PTS/confidence.

Full/Heavy source caches, model assets and backend choices are never replaced.
The current coarse Full person gate is retained; rejected Lite results stay raw.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import time
from extract_exercise_quality_features import ROOT,DATA,FEATURES,decoded_frames,geometry
from extract_image_reference_candidates import checksum,code_checksum,read_cache,same_current_subject

PROTOCOL=ROOT/'models/lite-reference-protocol.json'
MODEL=DATA/'lite-model-research/pose_landmarker_lite.task'
SHA='59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a'
PIPELINE='mp33-lite-video-coarse-bound-research/1'


def validate_protocol(spec):
    if (spec.get('schema')!='senpai-lite-reference-protocol/1' or spec.get('model_sha256')!=SHA or
        spec.get('preprocessing_id')!=PIPELINE or spec.get('reference_only') is not True or
        spec.get('allow_final_test_access') is not False or spec.get('allow_validation_access') is not False or
        spec.get('sample_period_ms')!=66 or spec.get('maximum_duration_ms','missing') is not None or
        spec.get('long_edge_cap_px')!=960 or spec.get('coarse_minimum_confidence')!=.65 or
        spec.get('same_subject_torso_fraction')!=.5 or spec.get('same_subject_floor_normalized')!=.05):
        raise ValueError('Frozen Lite candidate identity/scope changed')
    manifest=ROOT/spec['manifest_path']
    if checksum(manifest)!=spec['manifest_sha256']:raise ValueError('Original manifest changed')
    expected=sorted((r['file'],r['sha256']) for r in json.loads(manifest.read_bytes())['videos']
        if r['subject'] in ['001','005'] and r['quality']=='good')
    if len(expected)!=16 or expected!=[(r['video'],r['source_sha256']) for r in spec['references']]:
        raise ValueError('Frozen sixteen-reference cohort changed')
    for r in spec['receipts']:
        actual=code_checksum(ROOT/r['path']) if 'sha256_lf' in r else checksum(ROOT/r['path'])
        if actual!=r.get('sha256_lf',r.get('sha256')):raise ValueError('Frozen source/input changed: '+r['path'])


def process_reference(entry):
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    source=DATA/'videos-v3'/entry['video'];coarse_file=ROOT/entry['coarse_cache_path']
    if checksum(source)!=entry['source_sha256'] or checksum(coarse_file)!=entry['coarse_cache_sha256'] or checksum(MODEL)!=SHA:
        raise ValueError('Frozen RGB/coarse/Lite identity changed')
    coarse=read_cache(coarse_file)
    if coarse['source_video_sha256']!=entry['source_sha256'] or coarse['maximum_sampled_duration_ms'] is not None:
        raise ValueError('Complete reference identity changed')
    path=DATA/'lite-reference-cache'/(source.stem+'.json.gz')
    if path.exists():raise FileExistsError('Preserve completed raw Lite evidence')
    options=vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,num_poses=1,min_pose_detection_confidence=.5,
        min_pose_presence_confidence=.5,min_tracking_confidence=.5)
    decoder=decoded_frames(source,66,None);prior_rows=iter(coarse['frames']);rows=[]
    try:
        with vision.PoseLandmarker.create_from_options(options) as estimator:
            for timestamp,image,_ in decoder:
                prior=next(prior_rows);h,w=image.shape[:2]
                if prior['timestamp_ms']!=timestamp or prior['image_width']!=w or prior['image_height']!=h:
                    raise ValueError('Original RGB/PTS and coarse evidence disagree')
                started=time.perf_counter()
                result=estimator.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,data=image),timestamp)
                elapsed=(time.perf_counter()-started)*1000
                raw=[];world_confidence=[]
                if result.pose_landmarks and result.pose_world_landmarks:
                    p,world=result.pose_landmarks[0],result.pose_world_landmarks[0]
                    if len(p)!=33 or len(world)!=33:raise ValueError('Expected raw MP33 outputs')
                    raw=[[a.x,a.y,a.z,a.visibility,a.presence,b.x,b.y,b.z] for a,b in zip(p,world)]
                    world_confidence=[[b.visibility,b.presence] for b in world]
                bound=same_current_subject(prior['landmarks'],raw)
                rows.append(dict(timestamp_ms=timestamp,image_width=w,image_height=h,landmarks=raw if bound else [],
                    raw_input_landmarks=raw,raw_world_visibility_presence=world_confidence,
                    features=geometry([p[5:8] for p in raw],[min(p[3:5]) for p in raw]) if bound else [None]*len(FEATURES),
                    input_rgb_sha256=hashlib.sha256(image.tobytes()).hexdigest(),coarse_timestamp_ms=timestamp,
                    subject_bound=bound,skip_reason=None if bound else 'LITE_PERSON_NOT_BOUND_TO_CURRENT_COARSE_PERSON',
                    research_inference_ms=elapsed))
    finally:decoder.close()
    if next(prior_rows,None) is not None or not rows:raise ValueError('Reference timestamps truncated')
    payload={k:coarse[k] for k in ['video','subject','exercise','quality','view','source_video_sha256',
        'sample_period_ms','maximum_sampled_duration_ms','source_duration_ms','feature_names']}
    payload.update(schema='mp33-lite-reference-research/1',model_sha256=SHA,model_variant='lite',preprocessing_id=PIPELINE,
        coarse_cache_sha256=entry['coarse_cache_sha256'],runtime='MediaPipe0.10.32 Lite VIDEO CPU Python; same-frame Full person binding',frames=rows)
    path.parent.mkdir(exist_ok=True)
    with gzip.open(path,'wt',encoding='utf-8') as stream:json.dump(payload,stream,separators=(',',':'),allow_nan=False)
    return dict(video=source.name,observations=len(rows),admitted_observations=sum(r['subject_bound'] for r in rows),
        raw_model_outputs=sum(bool(r['raw_input_landmarks']) for r in rows),cache_path=path.relative_to(ROOT).as_posix(),cache_sha256=checksum(path))


def run(workers):
    if not 1<=workers<=3:raise ValueError('One to three bounded desktop workers only')
    spec=json.loads(PROTOCOL.read_bytes());validate_protocol(spec)
    output=DATA/'lite-reference-extraction.json'
    if output.exists():raise FileExistsError('Preserve previous Lite experiment')
    started=time.monotonic();records=[];failures=[]
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        jobs={pool.submit(process_reference,r):r for r in spec['references']}
        for job in concurrent.futures.as_completed(jobs):
            try:
                records.append(job.result());print(f'Lite {len(records)}/16 {jobs[job]["video"]}',flush=True)
            except Exception as error:failures.append(dict(video=jobs[job]['video'],error=repr(error)))
    report=dict(schema='senpai-lite-reference-extraction/1',protocol_sha256=checksum(PROTOCOL),model_sha256=SHA,
        records=sorted(records,key=lambda r:r['video']),failures=failures,seconds=time.monotonic()-started,
        production_changed=False,source_replay_performed=False,human_rep_precision=None)
    output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    if failures or len(records)!=16:raise RuntimeError('Incomplete Lite extraction; preserve failure evidence')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--workers',type=int,default=3)
    run(parser.parse_args().workers)
