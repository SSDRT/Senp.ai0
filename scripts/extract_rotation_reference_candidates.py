"""Frozen current-frame quarter-turn reference extraction; no source labels/fitting.

All original model coordinates remain in raw_input_landmarks. Canonical points
are only an inverse rigid/image transform, not inferred joints or repaired pose.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import math
import time
import numpy as np
from extract_exercise_quality_features import ROOT, DATA, POSE_MODELS, FEATURES, decoded_frames, geometry
from extract_image_reference_candidates import checksum, code_checksum, read_cache, usable, same_current_subject, TORSO

PROTOCOL = ROOT/'models/rotation-reference-protocol.json'
PIPELINES = {90: 'mp33-coarse-bound-cw90-image/1', 270: 'mp33-coarse-bound-cw270-image/1'}


def rotate_rgb(pixels, clockwise):
    if clockwise not in PIPELINES or pixels.ndim != 3 or pixels.shape[2] != 3:
        raise ValueError('Only frozen quarter-turn RGB inputs are permitted')
    return np.ascontiguousarray(np.rot90(pixels, -(clockwise//90)))


def map_to_original(points, clockwise, width, height):
    if clockwise not in PIPELINES or type(width) is not int or type(height) is not int or min(width, height) <= 0:
        raise ValueError('Original nonzero dimensions and frozen quarter-turn required')
    if not points:
        return []
    if len(points) != 33 or any(len(p) != 8 or not all(math.isfinite(v) for v in p) for p in points):
        raise ValueError('Raw MP33 image/world/confidence required')
    mapped = []
    for x,y,z,visibility,presence,wx,wy,wz in points:
        if clockwise == 90:
            mapped.append([y,1-x,z*height/width,visibility,presence,wy,-wx,wz])
        else:
            mapped.append([1-y,x,z*height/width,visibility,presence,-wy,wx,wz])
    return mapped


def validate_protocol(spec):
    if (spec.get('schema') != 'senpai-rotation-reference-protocol/1' or spec.get('reference_only') is not True or
            spec.get('allow_final_test_access') is not False or spec.get('sample_period_ms') != 66 or
            spec.get('maximum_duration_ms', 'missing') is not None or spec.get('clockwise_degrees') != [90,270] or
            spec.get('coarse_minimum_confidence') != .65 or spec.get('long_edge_cap_px') != 960 or
            spec.get('same_subject_torso_fraction') != .5 or spec.get('same_subject_floor_normalized') != .05):
        raise ValueError('Frozen rotation scope/parameters changed')
    manifest = ROOT/spec['manifest_path']
    if checksum(manifest) != spec['manifest_sha256']:
        raise ValueError('Original reference manifest changed')
    expected = sorted((r['file'],r['sha256']) for r in json.loads(manifest.read_bytes())['videos']
        if r['subject'] in ['001','005'] and r['quality'] == 'good')
    actual = [(r['video'],r['source_sha256']) for r in spec['references']]
    if len(expected) != 16 or len(actual) != 16 or expected != actual:
        raise ValueError('Frozen sixteen-reference cohort changed')
    for r in spec['receipts']:
        actual_hash = code_checksum(ROOT/r['path']) if 'sha256_lf' in r else checksum(ROOT/r['path'])
        if actual_hash != r.get('sha256_lf',r.get('sha256')):
            raise ValueError('Frozen source/input changed: '+r['path'])


def process_reference(entry):
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    source = DATA/'videos-v3'/entry['video']
    coarse_file = ROOT/entry['coarse_cache_path']
    if checksum(source) != entry['source_sha256'] or checksum(coarse_file) != entry['coarse_cache_sha256']:
        raise ValueError('Original reference/coarse cache changed')
    coarse = read_cache(coarse_file)
    model, model_sha = POSE_MODELS['full']
    if (checksum(model) != model_sha or coarse['model_sha256'] != model_sha or
            coarse['source_video_sha256'] != entry['source_sha256'] or coarse['maximum_sampled_duration_ms'] is not None):
        raise ValueError('Reference/model identity changed')
    paths = {turn: DATA/f'rotation-reference-cw{turn}-cache'/(source.stem+'.json.gz') for turn in PIPELINES}
    if any(p.exists() for p in paths.values()):
        raise FileExistsError('Preserve earlier rotation raw evidence')
    options = vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.IMAGE,num_poses=1,min_pose_detection_confidence=.5,
        min_pose_presence_confidence=.5,min_tracking_confidence=.5)
    rows = {turn: [] for turn in PIPELINES}
    prior_rows = iter(coarse['frames'])
    decoder = decoded_frames(source,66,None)
    try:
        with vision.PoseLandmarker.create_from_options(options) as estimator:
            for timestamp,pixels,_ in decoder:
                prior = next(prior_rows)
                h,w = pixels.shape[:2]
                if prior['timestamp_ms'] != timestamp or prior['image_width'] != w or prior['image_height'] != h:
                    raise ValueError('Original PTS/RGB and coarse evidence disagree')
                for turn in PIPELINES:
                    row = dict(timestamp_ms=timestamp,image_width=w,image_height=h,clockwise_degrees=turn,
                        rotated_width=h,rotated_height=w,landmarks=[],raw_input_landmarks=[],
                        raw_world_visibility_presence=[],features=[None]*len(FEATURES),subject_bound=False,
                        coarse_timestamp_ms=timestamp,skip_reason=None,research_inference_ms=0.0)
                    if not usable(prior['landmarks'],TORSO):
                        row['skip_reason'] = 'CURRENT_COARSE_PERSON_UNAVAILABLE'
                    else:
                        image = rotate_rgb(pixels,turn)
                        row['input_rgb_sha256'] = hashlib.sha256(image.tobytes()).hexdigest()
                        started = time.perf_counter()
                        result = estimator.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=image))
                        row['research_inference_ms'] = (time.perf_counter()-started)*1000
                        raw = []
                        if result.pose_landmarks and result.pose_world_landmarks:
                            p,world = result.pose_landmarks[0],result.pose_world_landmarks[0]
                            if len(p) != 33 or len(world) != 33:
                                raise ValueError('Raw MP33 result length changed')
                            raw = [[a.x,a.y,a.z,a.visibility,a.presence,b.x,b.y,b.z] for a,b in zip(p,world)]
                            row['raw_world_visibility_presence'] = [[b.visibility,b.presence] for b in world]
                        row['raw_input_landmarks'] = raw
                        mapped = map_to_original(raw,turn,w,h)
                        row['subject_bound'] = same_current_subject(prior['landmarks'],mapped)
                        if row['subject_bound']:
                            row['landmarks'] = mapped
                            row['features'] = geometry([p[5:8] for p in mapped],[min(p[3:5]) for p in mapped])
                        else:
                            row['skip_reason'] = 'ROTATED_PERSON_NOT_BOUND_TO_CURRENT_COARSE_PERSON'
                    rows[turn].append(row)
    finally:
        decoder.close()
    if next(prior_rows,None) is not None or not rows[90]:
        raise ValueError('Original reference timestamps truncated')
    records = []
    for turn,path in paths.items():
        payload = {k:coarse[k] for k in ['video','subject','exercise','quality','view','source_video_sha256','model_sha256',
            'sample_period_ms','maximum_sampled_duration_ms','source_duration_ms','feature_names']}
        payload.update(schema='mp33-quarter-turn-reference/1',preprocessing_id=PIPELINES[turn],
            coarse_cache_sha256=entry['coarse_cache_sha256'],clockwise_degrees=turn,
            runtime='MediaPipe0.10.32 Full IMAGE CPU Python, exact quarter-turn RGB, inverse canonical mapping',frames=rows[turn])
        path.parent.mkdir(exist_ok=True)
        with gzip.open(path,'wt',encoding='utf-8') as stream:
            json.dump(payload,stream,separators=(',',':'),allow_nan=False)
        records.append(dict(video=source.name,clockwise_degrees=turn,observations=len(rows[turn]),
            admitted_observations=sum(r['subject_bound'] for r in rows[turn]),
            model_outputs_retained=sum(bool(r['raw_input_landmarks']) for r in rows[turn]),
            cache_path=path.relative_to(ROOT).as_posix(),cache_sha256=checksum(path)))
    return records


def run(workers):
    if not 1 <= workers <= 3:
        raise ValueError('One to three bounded desktop workers only')
    spec = json.loads(PROTOCOL.read_bytes());validate_protocol(spec)
    output = DATA/'rotation-reference-extraction.json'
    if output.exists():
        raise FileExistsError('Preserve completed experiment')
    started = time.monotonic();records,failures = [],[]
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(process_reference,r):r for r in spec['references']}
        for job in concurrent.futures.as_completed(jobs):
            try:
                records.extend(job.result())
                print(f'Quarter-turn {len(records)//2}/16 {jobs[job]["video"]}',flush=True)
            except Exception as error:
                failures.append(dict(video=jobs[job]['video'],error=repr(error)))
    report = dict(schema='senpai-rotation-reference-extraction/1',protocol_sha256=checksum(PROTOCOL),
        records=sorted(records,key=lambda r:(r['video'],r['clockwise_degrees'])),failures=failures,
        seconds=time.monotonic()-started,production_changed=False,source_replay_performed=False,human_rep_precision=None)
    output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    if failures or len(records) != 32:
        raise RuntimeError('Incomplete extraction; retain failure report')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument('--workers',type=int,default=3)
    run(parser.parse_args().workers)
