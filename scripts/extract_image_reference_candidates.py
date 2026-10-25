"""Frozen, reference-only Full IMAGE and coarse-guided crop IMAGE experiments.

Both use observed current-frame RGB and coarse VIDEO evidence. No future frames,
source outcomes, synthetic points, confidence increases or pose repair are used.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import math
import time

from extract_exercise_quality_features import ROOT, DATA, POSE_MODELS, FEATURES, decoded_frames, geometry

PROTOCOL = ROOT/'models/image-reference-extraction-protocol.json'
PIPELINES = {'full_image': 'mp33-coarse-bound-full-image/1', 'coarse_roi_image': 'mp33-coarse-bound-roi-image/1'}
TORSO = (11, 12, 23, 24)
CROP_ANCHORS = TORSO+(15, 16, 27, 28)


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def code_checksum(path):
    return hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()


def usable(points, indices):
    return (len(points) == 33 and all(len(p) == 8 and all(math.isfinite(v) for v in p) for p in points) and
        all(min(points[i][3:5]) >= .65 and 0 <= points[i][0] <= 1 and 0 <= points[i][1] <= 1 for i in indices))


def crop_bounds(points, width, height):
    if width < 64 or height < 64 or not usable(points, CROP_ANCHORS):
        return None
    seen = [p for p in points if min(p[3:5]) >= .65]
    xs, ys = [p[0]*width for p in seen], [p[1]*height for p in seen]
    dx, dy = max(xs)-min(xs), max(ys)-min(ys)
    if dx <= 0 or dy <= 0:
        return None
    bounds = (max(0, math.floor(min(xs)-.2*dx)), max(0, math.floor(min(ys)-.2*dy)),
              min(width, math.ceil(max(xs)+.2*dx)), min(height, math.ceil(max(ys)+.2*dy)))
    return bounds if bounds[2]-bounds[0] >= 64 and bounds[3]-bounds[1] >= 64 else None


def map_to_original(points, bounds, width, height):
    if not points:
        return []
    x0, y0, x1, y1 = bounds
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height) or len(points) != 33:
        raise ValueError('Invalid crop or raw MP33 count')
    if any(len(p) != 8 or not all(math.isfinite(v) for v in p) for p in points):
        raise ValueError('Invalid raw model point')
    # z is width-normalized image depth. Preserve all original local values too.
    # Metric world coordinates, visibility and presence are copied byte-for-value.
    return [[(x0+p[0]*(x1-x0))/width, (y0+p[1]*(y1-y0))/height, p[2]*(x1-x0)/width, *p[3:]] for p in points]


def same_current_subject(coarse, refined):
    if not usable(coarse, TORSO) or not usable(refined, TORSO):
        return False
    def center(points, indices):
        return tuple(sum(points[i][axis] for i in indices)/len(indices) for axis in (0, 1))
    a, b = center(coarse, TORSO), center(refined, TORSO)
    shoulders, hips = center(coarse, (11, 12)), center(coarse, (23, 24))
    torso = math.dist(shoulders, hips)
    return torso > 0 and math.dist(a, b) <= max(.05, .5*torso)


def validate_protocol(protocol, manifest):
    if (protocol.get('schema') != 'senpai-image-reference-research/1' or protocol.get('reference_only') is not True or
            protocol.get('allow_final_test_access') is not False or protocol.get('sample_period_ms') != 66 or
            protocol.get('maximum_duration_ms', 'missing') is not None or protocol.get('pipelines') != PIPELINES or
            protocol.get('long_edge_cap_px') != 960 or protocol.get('crop_padding_fraction') != .2 or
            protocol.get('coarse_minimum_confidence') != .65 or protocol.get('minimum_crop_edge_px') != 64 or
            protocol.get('subject_agreement_torso_fraction') != .5 or protocol.get('subject_agreement_floor_normalized') != .05):
        raise ValueError('Changed experiment scope or parameters')
    expected = sorted([r for r in manifest['videos'] if r['subject'] in ['001', '005'] and r['quality'] == 'good'], key=lambda r: r['file'])
    declared = protocol.get('references', [])
    if (len(expected) != 16 or len({r['file'] for r in expected}) != 16 or
            [(r['video'], r['source_sha256']) for r in declared] != [(r['file'], r['sha256']) for r in expected]):
        raise ValueError('Changed camera reference cohort')
    return expected


def read_cache(path):
    with gzip.open(path, 'rt') as stream:
        return json.load(stream)


def process_reference(entry):
    import mediapipe as mp
    import numpy as np
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    source = DATA/'videos-v3'/entry['video']
    coarse_path = ROOT/entry['coarse_cache_path']
    if checksum(source) != entry['source_sha256'] or checksum(coarse_path) != entry['coarse_cache_sha256']:
        raise ValueError('Reference/coarse evidence changed')
    coarse = read_cache(coarse_path)
    if coarse['source_video_sha256'] != entry['source_sha256'] or coarse['maximum_sampled_duration_ms'] is not None:
        raise ValueError('Coarse reference identity or duration mismatch')
    model, model_sha = POSE_MODELS['full']
    if checksum(model) != model_sha or coarse['model_sha256'] != model_sha:
        raise ValueError('Full model identity changed')
    destinations = {v: DATA/f'image-reference-{v}-cache'/(source.stem+'.json.gz') for v in PIPELINES}
    if any(p.exists() for p in destinations.values()):
        raise FileExistsError('Preserve prior raw candidate caches; no overwriting')
    options = vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.IMAGE, num_poses=1, min_pose_detection_confidence=.5,
        min_pose_presence_confidence=.5, min_tracking_confidence=.5)
    rows = {v: [] for v in PIPELINES}
    current_rows = iter(coarse['frames'])
    decoder = decoded_frames(source, 66, None)
    try:
        with vision.PoseLandmarker.create_from_options(options) as estimator:
            for timestamp, pixels, _ in decoder:
                prior = next(current_rows)
                h, w = pixels.shape[:2]
                if (prior['timestamp_ms'] != timestamp or prior['image_width'] != w or prior['image_height'] != h):
                    raise ValueError('Current RGB and coarse observation are not the same original frame')
                coarse_points = prior['landmarks']
                for variant in PIPELINES:
                    bounds = (0, 0, w, h) if variant == 'full_image' else crop_bounds(coarse_points, w, h)
                    row = {'timestamp_ms': timestamp, 'image_width': w, 'image_height': h,
                        'landmarks': [], 'raw_input_landmarks': [], 'raw_world_visibility_presence': [], 'features': [None]*len(FEATURES),
                        'coarse_timestamp_ms': timestamp, 'crop_bounds_xyxy': list(bounds) if bounds else None,
                        'subject_bound': False, 'skip_reason': None, 'research_inference_ms': 0.0}
                    if bounds is None or not usable(coarse_points, TORSO):
                        row['skip_reason'] = 'CURRENT_COARSE_PERSON_UNAVAILABLE'
                    else:
                        x0, y0, x1, y1 = bounds
                        image = np.ascontiguousarray(pixels[y0:y1, x0:x1])
                        row['input_rgb_sha256'] = hashlib.sha256(image.tobytes()).hexdigest()
                        started = time.perf_counter()
                        result = estimator.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=image))
                        row['research_inference_ms'] = (time.perf_counter()-started)*1000
                        raw = []
                        if result.pose_landmarks and result.pose_world_landmarks:
                            p, world = result.pose_landmarks[0], result.pose_world_landmarks[0]
                            if len(p) != 33 or len(world) != 33:
                                raise ValueError('Unexpected raw MP33 count')
                            raw = [[a.x,a.y,a.z,a.visibility,a.presence,b.x,b.y,b.z] for a,b in zip(p,world)]
                            row['raw_world_visibility_presence'] = [[b.visibility,b.presence] for b in world]
                        row['raw_input_landmarks'] = raw
                        mapped = map_to_original(raw, bounds, w, h)
                        row['subject_bound'] = same_current_subject(coarse_points, mapped)
                        if row['subject_bound']:
                            row['landmarks'] = mapped
                            row['features'] = geometry([p[5:8] for p in mapped], [min(p[3:5]) for p in mapped])
                        else:
                            row['skip_reason'] = 'REFINED_PERSON_NOT_BOUND_TO_CURRENT_COARSE_PERSON'
                    rows[variant].append(row)
    finally:
        decoder.close()
    if next(current_rows, None) is not None or not rows['full_image']:
        raise ValueError('Original reference timestamps were truncated')
    records = []
    for variant, path in destinations.items():
        payload = {k: coarse[k] for k in ['video','subject','exercise','quality','view','source_video_sha256','model_sha256',
            'sample_period_ms','maximum_sampled_duration_ms','source_duration_ms','feature_names']}
        payload.update(schema='mp33-image-refinement-research/1', preprocessing_id=PIPELINES[variant],
            coarse_cache_sha256=entry['coarse_cache_sha256'], runtime='MediaPipe 0.10.32 Full IMAGE CPU Python; same-frame coarse VIDEO crop/subject binding',
            frames=rows[variant])
        path.parent.mkdir(exist_ok=True)
        with gzip.open(path, 'wt', encoding='utf-8') as stream:
            json.dump(payload, stream, separators=(',', ':'), allow_nan=False)
        records.append({'video': source.name, 'variant': variant, 'observations': len(rows[variant]),
            'admitted_observations': sum(r['subject_bound'] for r in rows[variant]),
            'model_outputs_retained': sum(bool(r['raw_input_landmarks']) for r in rows[variant]),
            'cache_path': path.relative_to(ROOT).as_posix(), 'cache_sha256': checksum(path)})
    return records


def run(workers):
    if not 1 <= workers <= 3:
        raise ValueError('Use one to three bounded workers')
    spec = json.loads(PROTOCOL.read_text())
    manifest = ROOT/spec['manifest_path']
    if checksum(manifest) != spec['manifest_sha256']:
        raise ValueError('Original manifest changed')
    validate_protocol(spec, json.loads(manifest.read_text()))
    for r in spec['source_receipts']:
        actual = code_checksum(ROOT/r['path']) if 'sha256_lf' in r else checksum(ROOT/r['path'])
        if actual != r.get('sha256_lf', r.get('sha256')):
            raise ValueError('Frozen source/registry changed: '+r['path'])
    output = DATA/'image-reference-extraction.json'
    if output.exists():
        raise FileExistsError('Preserve completed experiment')
    started = time.monotonic()
    records, failures = [], []
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(process_reference, r): r for r in spec['references']}
        for job in concurrent.futures.as_completed(jobs):
            try:
                records.extend(job.result())
                print(f'IMAGE {len(records)//2}/16 {jobs[job]["video"]}', flush=True)
            except Exception as error:
                failures.append({'video': jobs[job]['video'], 'error': repr(error)})
    result = {'schema': 'senpai-image-reference-extraction/1', 'kind': 'reference-only-preprocessing-research-not-human-rep-accuracy',
        'protocol_sha256': checksum(PROTOCOL), 'videos': records, 'failures': failures,
        'seconds': time.monotonic()-started, 'source_replay_performed': False, 'production_changed': False}
    output.write_text(json.dumps(result, indent=2)+'\n', newline='\n')
    if failures or len(records) != 32:
        raise RuntimeError('Incomplete candidate extraction; preserve failure evidence')
    print(json.dumps({'seconds': result['seconds'], 'failures': failures}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workers', type=int, default=3)
    run(parser.parse_args().workers)
