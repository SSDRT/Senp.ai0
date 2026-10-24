"""Export audited development-only marker descriptors and original rep boundaries."""
import hashlib
import json
from pathlib import Path
import zipfile
import numpy as np
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import NAME, parse_publisher_array, read_development_member
from uiprmd_vicon import BACKEND_SHA256, PIPELINE, SAMPLE_PERIOD_MS, SEGMENT_EXPORT_TOLERANCE_MM, aligned_segment_bounds, measurement_features, markers, INDEX


def prepare(destination=None):
    protocol_path = ROOT/'models/uiprmd-evaluation-protocol.json'
    protocol = json.loads(protocol_path.read_text())
    allowed = set(protocol['subjects']['development'])
    if allowed != {'01', '02', '03', '04'}:
        raise ValueError('Frozen development split changed')
    destination = destination or DATA/'development-vicon'
    destination.mkdir(parents=True, exist_ok=True)
    acquisition = json.loads((DATA/'acquisition.json').read_text())
    for info in acquisition['files']:
        if info['file'].endswith('.zip') and checksum(DATA/info['file'], 'sha256') != info['sha256']:
            raise ValueError('Archive changed since acquisition')
    rows, reads, diagnostics = [], [], []
    for incorrect in [False, True]:
        full_archive = 'Incorrect Movements.zip' if incorrect else 'Movements.zip'
        segmented_archive = 'Incorrect Segmented Movements.zip' if incorrect else 'Segmented Movements.zip'
        with zipfile.ZipFile(DATA/full_archive) as full_zip, zipfile.ZipFile(DATA/segmented_archive) as seg_zip:
            def read(archive, archive_name, member):
                raw = read_development_member(archive, member, allowed)
                reads.append({'archive': archive_name, 'member': member, 'sha256': hashlib.sha256(raw).hexdigest()})
                return parse_publisher_array(raw, 'Vicon')[0], raw
            for member in sorted(full_zip.namelist()):
                match = NAME.fullmatch(Path(member).name)
                if (not match or match['subject'] not in allowed or '/Vicon/Positions/' not in member or
                    'm'+match['movement'] not in protocol['variants'] or match['episode'] is not None):
                    continue
                array, raw = read(full_zip, full_archive, member)
                movement = 'm'+match['movement']
                variant = protocol['variants'][movement]
                episodes = []
                for seg_member in sorted(seg_zip.namelist()):
                    seg = NAME.fullmatch(Path(seg_member).name)
                    if (not seg or '/Vicon/Positions/' not in seg_member or seg['subject'] != match['subject'] or
                        seg['movement'] != match['movement'] or seg['episode'] is None):
                        continue
                    segmented, _ = read(seg_zip, segmented_archive, seg_member)
                    try:
                        start, end = aligned_segment_bounds(array, segmented)
                    except ValueError as error:
                        raise ValueError(f'{member} / {seg_member}: {error}') from error
                    episodes.append({'episode': int(seg['episode']), 'start_frame': start, 'end_frame': end,
                                     'start_ms': start*SAMPLE_PERIOD_MS, 'end_ms': end*SAMPLE_PERIOD_MS,
                                     'correct': not incorrect, 'member': seg_member,
                                     'maximum_export_difference_mm': float(np.max(np.abs(array[start:end+1]-segmented)))})
                if len(episodes) != 10 or any(a['end_frame'] >= b['start_frame'] for a,b in zip(episodes,episodes[1:])):
                    raise ValueError('Expected ten ordered, nonoverlapping original rep excerpts')
                features = measurement_features(array)
                frames = [{'timestamp_ms': i*SAMPLE_PERIOD_MS,
                           'values': {key: float(values[i]) for key,values in features.items() if np.isfinite(values[i])}}
                          for i in range(len(array))]
                identifier = f'{movement}-s{match["subject"]}-'+('incorrect' if incorrect else 'correct')
                record = {'schema': 'uiprmd-vicon-recording/1', 'measurement_domain': 'skeletal',
                          'pipeline': PIPELINE, 'backend_sha256': BACKEND_SHA256, 'subject': match['subject'],
                          'source_member': member, 'source_sha256': hashlib.sha256(raw).hexdigest(),
                          'movement': movement, 'exercise_id': variant['policy'], 'side': variant['selected_side'],
                          'correct': not incorrect, 'sample_period_ms': SAMPLE_PERIOD_MS,
                          'duration_ms': len(array)*SAMPLE_PERIOD_MS, 'episodes': episodes, 'frames': frames}
                target = destination/(identifier+'.json')
                target.write_text(json.dumps(record, separators=(',', ':'), allow_nan=False)+'\n')
                points = markers(array)
                pairs = [('LSHO','LELB'),('RSHO','RELB'),('LKNE','LANK'),('RKNE','RANK')]
                distances = {a+'-'+b: np.quantile(np.linalg.norm(points[:,INDEX[a]]-points[:,INDEX[b]],axis=1),[0,.5,1]).tolist() for a,b in pairs}
                diagnostics.append({'recording': identifier, 'frames': len(array), 'aligned_segments': len(episodes),
                                    'missing_zero_marker_samples': int(np.count_nonzero(np.linalg.norm(points,axis=2)==0)),
                                    'nonfinite_marker_samples': int(np.count_nonzero(~np.isfinite(points).all(axis=2))),
                                    'limb_marker_distances_mm_min_median_max': distances,
                                    'angle_ranges': {k: [float(np.nanmin(v)), float(np.nanmax(v))] for k,v in features.items()}})
                rows.append({'id': identifier, 'file': target.name, 'sha256': checksum(target,'sha256'),
                             'subject': match['subject'], 'movement': movement, 'correct': not incorrect})
    manifest = {'schema': 'uiprmd-development-export/1', 'pipeline': PIPELINE, 'backend_sha256': BACKEND_SHA256,
                'protocol_sha256': checksum(protocol_path,'sha256'), 'measurement_domain': 'skeletal',
                'segment_export_absolute_tolerance_mm': SEGMENT_EXPORT_TOLERANCE_MM,
                'confidence_semantics': '1 only denotes observed finite nonzero marker geometry; not MediaPipe confidence',
                'subjects_opened': sorted(allowed), 'validation_trajectories_opened': False,
                'test_trajectories_opened': False, 'records': rows, 'reads': reads, 'diagnostics': diagnostics}
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2,allow_nan=False)+'\n')
    print(json.dumps({'recordings':len(rows),'aligned_reps':len(rows)*10,'subjects':sorted(allowed),'reads':len(reads)}))
    return manifest


if __name__ == '__main__':
    prepare()
