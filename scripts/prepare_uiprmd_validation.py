"""Audit original validation recordings after a committed candidate freeze.

This entry point never authorizes final-test access. Existing development guards
remain unchanged. No labels fit a model or choose a reference.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile
from datetime import datetime, timezone
import numpy as np
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import NAME, parse_publisher_array
from uiprmd_vicon import (BACKEND_SHA256, PIPELINE, SAMPLE_PERIOD_MS,
                         SEGMENT_EXPORT_TOLERANCE_MM, aligned_segment_bounds, measurement_features)

PROTOCOL = ROOT/'models/uiprmd-quality-validation-protocol.json'
FREEZE_COMMIT = '8a330b7'
SUBJECTS = {'05', '06', '07'}


def read_validation_member(archive, member):
    path = PurePosixPath(member.replace('\\', '/'))
    match = NAME.fullmatch(path.name)
    if ('..' in path.parts or path.is_absolute() or match is None or
        match['subject'] not in SUBJECTS or match['movement'] not in {'01', '07'} or
        match['kind'] != 'positions' or '/Vicon/Positions/' not in '/'+path.as_posix()):
        raise PermissionError('Only frozen validation Vicon Positions may be opened')
    return archive.read(member)


def frozen_candidate():
    protocol = json.loads(PROTOCOL.read_text())
    committed = subprocess.check_output(['git', 'show', FREEZE_COMMIT+':models/uiprmd-quality-validation-protocol.json'], cwd=ROOT)
    if protocol != json.loads(committed):
        raise ValueError('Validation candidate differs from its committed freeze')
    subprocess.run(['git', 'merge-base', '--is-ancestor', FREEZE_COMMIT, 'HEAD'], cwd=ROOT, check=True)
    if (set(protocol['validation_subjects']) != SUBJECTS or protocol['candidate'] != 'logistic' or
        protocol['veto_bad_score_threshold'] != .75 or protocol['evaluation']['allow_final_test_access'] or
        protocol['evaluation']['allow_fit_on_validation']):
        raise ValueError('Unexpected validation scope')
    for path, digest in protocol['source_code_sha256'].items():
        if checksum(ROOT/path, 'sha256') != digest:
            raise ValueError('Frozen feature/training/evaluation source changed: '+path)
    for artifact in protocol['fitted_models']:
        if checksum(ROOT/artifact['path'], 'sha256') != artifact['sha256']:
            raise ValueError('Frozen fitted model changed')
    development_result = DATA/'quality-head-development.json'
    if checksum(development_result, 'sha256') != protocol['development_result_sha256']:
        raise ValueError('Frozen development result changed')
    development = json.loads(development_result.read_text())
    if checksum(DATA/'development-replay.json', 'sha256') != development['source_replay_sha256']:
        raise ValueError('Frozen reference replay changed')
    replay = json.loads((DATA/'development-replay.json').read_text())
    if checksum(DATA/'development-vicon/manifest.json', 'sha256') != replay['manifest_sha256']:
        raise ValueError('Frozen reference manifest changed')
    if checksum(ROOT/'models/uiprmd-evaluation-protocol.json', 'sha256') != protocol['split_protocol_sha256']:
        raise ValueError('Frozen dataset split changed')
    return protocol


def prepare():
    protocol = frozen_candidate()  # Must precede every validation trajectory read.
    destination = DATA/'validation-vicon'
    destination.mkdir(exist_ok=True)
    acquisition = json.loads((DATA/'acquisition.json').read_text())
    for info in acquisition['files']:
        if info['file'].endswith('.zip') and checksum(DATA/info['file'], 'sha256') != info['sha256']:
            raise ValueError('Publisher archive changed')
    rows, reads = [], []
    # Append the access intention before reading, so a failed run remains auditable.
    audit_path = DATA/'validation-access.jsonl'
    with audit_path.open('a') as audit:
        def read(archive, archive_name, member):
            intent = {'member': member, 'archive': archive_name, 'candidate_commit': FREEZE_COMMIT,
                      'utc': datetime.now(timezone.utc).isoformat(),
                      'candidate_protocol_sha256': checksum(PROTOCOL, 'sha256')}
            audit.write(json.dumps(dict(intent, status='requested'))+'\n'); audit.flush()
            raw = read_validation_member(archive, member)
            receipt = dict(intent, sha256=hashlib.sha256(raw).hexdigest())
            reads.append(receipt)
            audit.write(json.dumps(dict(receipt, status='read'))+'\n'); audit.flush()
            return parse_publisher_array(raw, 'Vicon')[0], raw
        for incorrect in [False, True]:
            full_name = 'Incorrect Movements.zip' if incorrect else 'Movements.zip'
            seg_name = 'Incorrect Segmented Movements.zip' if incorrect else 'Segmented Movements.zip'
            with zipfile.ZipFile(DATA/full_name) as full, zipfile.ZipFile(DATA/seg_name) as segmented:
                for member in sorted(full.namelist()):
                    match = NAME.fullmatch(Path(member).name)
                    if (not match or match['subject'] not in SUBJECTS or match['movement'] not in {'01','07'} or
                        '/Vicon/Positions/' not in member or match['episode'] is not None):
                        continue
                    array, raw = read(full, full_name, member)
                    episodes = []
                    for cut in sorted(segmented.namelist()):
                        seg = NAME.fullmatch(Path(cut).name)
                        if (not seg or '/Vicon/Positions/' not in cut or seg['subject'] != match['subject'] or
                            seg['movement'] != match['movement'] or seg['episode'] is None):
                            continue
                        excerpt, _ = read(segmented, seg_name, cut)
                        start, end = aligned_segment_bounds(array, excerpt)
                        episodes.append({'episode': int(seg['episode']), 'start_frame': start, 'end_frame': end,
                                         'start_ms': start*SAMPLE_PERIOD_MS, 'end_ms': end*SAMPLE_PERIOD_MS,
                                         'correct': not incorrect, 'member': cut,
                                         'maximum_export_difference_mm': float(np.max(np.abs(array[start:end+1]-excerpt)))})
                    if len(episodes) != 10 or any(a['end_frame'] >= b['start_frame'] for a,b in zip(episodes,episodes[1:])):
                        raise ValueError('Expected ten ordered nonoverlapping original rep excerpts')
                    movement = 'm'+match['movement']
                    variant = json.loads((ROOT/'models/uiprmd-evaluation-protocol.json').read_text())['variants'][movement]
                    features = measurement_features(array)
                    identifier = f'{movement}-s{match["subject"]}-'+('incorrect' if incorrect else 'correct')
                    record = {'schema': 'uiprmd-vicon-recording/1', 'measurement_domain': 'skeletal',
                              'pipeline': PIPELINE, 'backend_sha256': BACKEND_SHA256, 'subject': match['subject'],
                              'source_member': member, 'source_sha256': hashlib.sha256(raw).hexdigest(),
                              'movement': movement, 'exercise_id': variant['policy'], 'side': variant['selected_side'],
                              'correct': not incorrect, 'sample_period_ms': SAMPLE_PERIOD_MS,
                              'duration_ms': len(array)*SAMPLE_PERIOD_MS, 'episodes': episodes,
                              'frames': [{'timestamp_ms': i*SAMPLE_PERIOD_MS,
                                          'values': {k: float(v[i]) for k,v in features.items() if np.isfinite(v[i])}}
                                         for i in range(len(array))]}
                    target = destination/(identifier+'.json')
                    target.write_text(json.dumps(record, separators=(',', ':'), allow_nan=False)+'\n')
                    rows.append({'id': identifier, 'file': target.name, 'sha256': checksum(target, 'sha256'),
                                 'subject': match['subject'], 'movement': movement, 'correct': not incorrect})
    if len(rows) != protocol['expected_recordings'] or len(reads) != 132:
        raise ValueError('Incomplete validation cohort')
    manifest = {'schema': 'uiprmd-validation-export/1', 'measurement_domain': 'skeletal',
                'pipeline': PIPELINE, 'backend_sha256': BACKEND_SHA256, 'subjects_opened': sorted(SUBJECTS),
                'candidate_freeze_commit': FREEZE_COMMIT, 'candidate_protocol_sha256': checksum(PROTOCOL, 'sha256'),
                'segment_export_absolute_tolerance_mm': SEGMENT_EXPORT_TOLERANCE_MM,
                'validation_trajectories_opened': True, 'test_trajectories_opened': False,
                'confidence_semantics': 'binary marker observability, not MediaPipe confidence',
                'records': rows, 'reads': reads}
    (destination/'manifest.json').write_text(json.dumps(manifest, indent=2, allow_nan=False)+'\n')
    print(json.dumps({'recordings': len(rows), 'reps': len(rows)*10, 'reads': len(reads), 'subjects': sorted(SUBJECTS)}))


if __name__ == '__main__':
    prepare()
