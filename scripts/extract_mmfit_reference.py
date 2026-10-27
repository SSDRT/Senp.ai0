"""Licensed RGB-only reference development. Machine activity proposals are not form labels.

Freeze the protocol before running. Coarse observations cannot count repetitions;
only a fresh graph on the first eligible original contiguous bout is calibrated.
"""
import argparse
import gzip
import hashlib
import json
import math
import time

from extract_exercise_quality_features import ROOT, POSE_MODELS, FEATURES, decoded_frames, geometry
from fetch_mmfit_reference_video import DATA, VIDEO, SIZE, MD5, file_hashes

PROTOCOL = ROOT/'models/mmfit-reference-protocol.json'
SOURCE_SHA = '8f387910a830757ce91df0301ed4d8b607c589d355ba3158917185823635fd36'
REQUIRED = (11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
PARAMETERS = dict(coarse_period_ms=500, fine_period_ms=66, minimum_confidence=.65,
    elbow_min_degrees=140, shoulder_min_degrees=35, shoulder_max_degrees=110,
    knee_min_degrees=80, knee_max_degrees=135, lateral_fraction_min=.60,
    normal_fraction_max=.45, proposal_gap_ms=2500, proposal_duration_ms=8000,
    proposal_observations_min=5, context_padding_ms=2000, long_edge_cap_px=960)


def sha(path, normalize=False):
    raw = path.read_bytes()
    return hashlib.sha256(raw.replace(b'\r\n', b'\n') if normalize else raw).hexdigest()


def validate_protocol(spec):
    if (spec.get('schema') != 'senpai-mmfit-reference/1' or spec.get('parameters') != PARAMETERS or
            spec.get('video') != VIDEO or spec.get('video_sha256') != SOURCE_SHA or
            spec.get('license') != 'CC BY 4.0' or spec.get('reference_only') is not True or
            spec.get('allow_final_test_access') is not False or spec.get('annotation_archive_access') is not False or
            spec.get('selection') != 'first-chronological-eligible-bout' or
            spec.get('exercise') != 'lateral_raise' or spec.get('variant') != 'seated-dumbbell-lateral-raise'):
        raise ValueError('Changed frozen RGB reference scope or geometry parameters')
    for receipt in spec['source_receipts']:
        normalized = 'sha256_lf' in receipt
        if sha(ROOT/receipt['path'], normalized) != receipt.get('sha256_lf', receipt.get('sha256')):
            raise ValueError('Frozen source changed: '+receipt['path'])


def unit(vector):
    length = math.sqrt(sum(x*x for x in vector))
    return [x/length for x in vector] if length > 1e-8 else None


def minus(a, b): return [x-y for x, y in zip(a, b)]
def dot(a, b): return sum(x*y for x, y in zip(a, b))


def angle(a, b, c):
    u, v = unit(minus(a, b)), unit(minus(c, b))
    return math.degrees(math.acos(max(-1, min(1, dot(u, v))))) if u and v else None


def activity_proposal(points):
    """Current raw world geometry only; no propagation, confidence repair or labels."""
    if (len(points) != 33 or any(len(p) != 8 or not all(math.isfinite(v) for v in p) for p in points) or
            any(min(points[i][3:5]) < .65 or not (0 <= points[i][0] <= 1 and 0 <= points[i][1] <= 1) for i in REQUIRED)):
        return False
    world = [p[5:8] for p in points]
    lateral = unit(minus(world[12], world[11]))
    vertical = unit([world[11][i]+world[12][i]-world[23][i]-world[24][i] for i in range(3)])
    if not lateral or not vertical: return False
    normal = unit([lateral[1]*vertical[2]-lateral[2]*vertical[1],
                   lateral[2]*vertical[0]-lateral[0]*vertical[2],
                   lateral[0]*vertical[1]-lateral[1]*vertical[0]])
    if normal is None: return False
    for shoulder, elbow, wrist, hip, knee, ankle in [(11, 13, 15, 23, 25, 27), (12, 14, 16, 24, 26, 28)]:
        upper = unit(minus(world[elbow], world[shoulder]))
        e = angle(world[shoulder], world[elbow], world[wrist])
        s = angle(world[elbow], world[shoulder], world[hip])
        k = angle(world[hip], world[knee], world[ankle])
        if (upper is None or e is None or s is None or k is None or e < 140 or not 35 <= s <= 110 or
                not 80 <= k <= 135 or abs(dot(upper, lateral)) < .60 or abs(dot(upper, normal)) > .45):
            return False
    return True


def propose_bouts(rows, duration_ms):
    if any(b['timestamp_ms'] <= a['timestamp_ms'] for a, b in zip(rows, rows[1:])):
        raise ValueError('Original timestamps must increase strictly')
    groups = []
    for row in rows:
        if not activity_proposal(row['landmarks']): continue
        timestamp = row['timestamp_ms']
        if not groups or timestamp-groups[-1][-1] > 2500: groups.append([])
        groups[-1].append(timestamp)
    return [dict(first_proposal_ms=g[0], last_proposal_ms=g[-1], proposal_observations=len(g),
                 start_ms=max(0, g[0]-2000), end_ms=min(math.floor(duration_ms), g[-1]+2000))
            for g in groups if len(g) >= 5 and g[-1]-g[0] >= 8000]


def extract(source, period, interval=None):
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    model, model_sha = POSE_MODELS['full']
    if sha(model) != model_sha: raise ValueError('Pinned Full model changed')
    options = vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1, min_pose_detection_confidence=.5,
        min_pose_presence_confidence=.5, min_tracking_confidence=.5)
    frames = []
    decoder = decoded_frames(source, period, None)
    try:
        # A fresh graph for each extraction; no coarse tracking state enters fine inference.
        with vision.PoseLandmarker.create_from_options(options) as estimator:
            for timestamp, image, duration_ms in decoder:
                if interval and timestamp < interval['start_ms']: continue
                if interval and timestamp > interval['end_ms']: break
                if frames and timestamp <= frames[-1]['timestamp_ms']: raise ValueError('Noncausal source timestamps')
                started = time.perf_counter()
                result = estimator.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=image), timestamp)
                row = dict(timestamp_ms=timestamp, image_width=image.shape[1], image_height=image.shape[0],
                    features=[None]*len(FEATURES), landmarks=[], raw_world_visibility_presence=[],
                    research_inference_ms=(time.perf_counter()-started)*1000)
                if result.pose_landmarks and result.pose_world_landmarks:
                    points, world = result.pose_landmarks[0], result.pose_world_landmarks[0]
                    if len(points) != 33 or len(world) != 33: raise ValueError('Unexpected raw landmark count')
                    row['landmarks'] = [[p.x,p.y,p.z,p.visibility,p.presence,w.x,w.y,w.z] for p,w in zip(points,world)]
                    row['raw_world_visibility_presence'] = [[w.visibility,w.presence] for w in world]
                    row['features'] = geometry([[w.x,w.y,w.z] for w in world], [min(p.visibility,p.presence) for p in points])
                frames.append(row)
                if len(frames)%500 == 0: print(f'MMFIT {period}ms {len(frames)} observations at {timestamp}ms', flush=True)
    finally: decoder.close()
    if not frames: raise ValueError('No original frames in declared interval')
    return dict(schema='mmfit-mp33-reference/1', video=VIDEO, source_video_sha256=SOURCE_SHA,
        model_sha256=model_sha, runtime='MediaPipe 0.10.32 Full VIDEO CPU Python', sample_period_ms=period,
        source_duration_ms=duration_ms, original_pts_preserved=True, interval=interval,
        feature_names=FEATURES, frames=frames, independent_form_labels=False,
        protocol_sha256=sha(PROTOCOL))


def write_cache(path, payload):
    if path.exists(): raise FileExistsError('Preserve raw evidence: '+str(path))
    with gzip.open(path, 'wt', encoding='utf-8') as stream:
        json.dump(payload, stream, separators=(',', ':'), allow_nan=False)


def run():
    spec = json.loads(PROTOCOL.read_text());validate_protocol(spec)
    source = DATA/VIDEO
    hashes = file_hashes(source)
    if source.stat().st_size != SIZE or hashes != dict(md5=MD5, sha256=SOURCE_SHA):
        raise ValueError('Original licensed video integrity mismatch')
    report_path = DATA/'reference-extraction.json'
    if report_path.exists() or (DATA/'coarse.json.gz').exists(): raise FileExistsError('Preserve completed/partial experiment')
    started = time.monotonic()
    coarse = extract(source, 500);write_cache(DATA/'coarse.json.gz', coarse)
    proposals = propose_bouts(coarse['frames'], coarse['source_duration_ms'])
    selected = proposals[0] if proposals else None
    report = dict(schema='mmfit-reference-extraction/1', protocol_sha256=sha(PROTOCOL),
        activity_proposals=proposals, selected=selected, coarse_observations=len(coarse['frames']),
        coarse_sha256=sha(DATA/'coarse.json.gz'), independent_form_labels=False,
        final_test_access=False, annotation_archive_access=False, production_changed=False)
    if selected:
        fine = extract(source, 66, selected);write_cache(DATA/'fine.json.gz', fine)
        report.update(fine_sha256=sha(DATA/'fine.json.gz'), fine_observations=len(fine['frames']))
    report['seconds'] = time.monotonic()-started
    report_path.write_text(json.dumps(report, indent=2)+'\n', newline='\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    run()
