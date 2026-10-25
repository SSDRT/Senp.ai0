"""Research-only 33 ms original-PTS extraction of the fixed 16 reference uploads.

Does not replace any existing cache, choose from source outcomes or access UI-PRMD.
The frozen protocol and input hashes are verified before launching inference.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
from pathlib import Path
import time

from extract_exercise_quality_features import ROOT, DATA, POSE_MODELS, SCHEMA, process_video

PROTOCOL = ROOT/'models/dense-reference-extraction-protocol.json'
PIPELINE = 'mp33-raw-world-dense33-research/1'


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_protocol(protocol, manifest):
    if protocol.get('schema') != 'senpai-dense-reference-research/1':
        raise ValueError('Unexpected dense extraction protocol')
    if (protocol.get('sample_period_ms') != 33 or protocol.get('maximum_duration_ms', 'missing') is not None or
            protocol.get('model') != 'full' or protocol.get('long_edge_cap_px') != 960 or protocol.get('preprocessing_id') != PIPELINE or
            protocol.get('reference_only') is not True or protocol.get('allow_final_test_access') is not False):
        raise ValueError('Dense research scope/parameters differ from the frozen experiment')
    expected = [r for r in manifest['videos'] if r['subject'] in ['001', '005'] and r['quality'] == 'good']
    expected = sorted(expected, key=lambda r: r['file'])
    if len(expected) != 16 or len({r['file'] for r in expected}) != 16:
        raise ValueError('Expected the original sixteen reference recordings')
    declared = protocol.get('references', [])
    if declared != [{'video': r['file'], 'source_sha256': r['sha256']} for r in expected]:
        raise ValueError('Reference cohort changed')
    return expected


def run(workers):
    if not 1 <= workers <= 3:
        raise ValueError('Use one to three bounded workers')
    protocol = json.loads(PROTOCOL.read_text())
    manifest_path = DATA/'rep-extraction-report.json'
    if checksum(manifest_path) != protocol['manifest_sha256']:
        raise ValueError('Original extraction manifest changed')
    selected = validate_protocol(protocol, json.loads(manifest_path.read_text()))
    for entry in protocol['source_receipts']:
        if checksum(ROOT/entry['path']) != entry['sha256']:
            raise ValueError('Frozen extraction source/selection changed: '+entry['path'])
    model, model_sha = POSE_MODELS['full']
    if model_sha != protocol['model_sha256'] or checksum(model) != model_sha:
        raise ValueError('Pinned Full model changed')
    for row in selected:
        if checksum(DATA/'videos-v3'/row['file']) != row['sha256']:
            raise ValueError('Original reference video changed')
    output = DATA/'dense-reference-full-cache'
    report_path = DATA/'dense-reference-full-extraction.json'
    if report_path.exists():
        raise FileExistsError('Preserve the completed extraction report; do not rerun over it')
    output.mkdir(exist_ok=True)
    # Existing files may only be exact-identity resumable caches from this experiment.
    allowed = {r['file'].removesuffix('.mp4')+'.json.gz' for r in selected}
    if any(p.name not in allowed for p in output.iterdir()):
        raise ValueError('Unexpected cache in research-only output')
    for path in output.glob('*.json.gz'):
        with gzip.open(path, 'rt') as stream:
            cache = json.load(stream)
        target = next(r for r in selected if path.name == r['file'].removesuffix('.mp4')+'.json.gz')
        if (cache.get('schema') != SCHEMA or cache.get('sample_period_ms') != 33 or cache.get('maximum_sampled_duration_ms', 'missing') is not None or
                cache.get('source_video_sha256') != target['sha256'] or cache.get('model_sha256') != model_sha):
            raise ValueError('Refuse to replace a different existing extraction: '+path.name)
    records, failures = [], []
    start = time.monotonic()
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        jobs = {pool.submit(process_video, str(DATA/'videos-v3'/r['file']), 33, str(output), 'full', None): r for r in selected}
        for future in concurrent.futures.as_completed(jobs):
            row = jobs[future]
            try:
                name, _ = future.result()
                path = output/(name.removesuffix('.mp4')+'.json.gz')
                with gzip.open(path, 'rt') as stream:
                    cache = json.load(stream)
                times = [f['timestamp_ms'] for f in cache['frames']]
                if not times or any(b <= a for a, b in zip(times, times[1:])):
                    raise ValueError('Noncausal source timestamps')
                if any(len(f['landmarks']) not in (0, 33) or any(len(p) != 8 for p in f['landmarks']) for f in cache['frames']):
                    raise ValueError('Raw MP33 fields were not retained')
                records.append({'video': name, 'source_sha256': cache['source_video_sha256'],
                    'model_sha256': cache['model_sha256'], 'observations': len(times),
                    'first_pts_ms': times[0], 'last_pts_ms': times[-1], 'source_duration_ms': cache['source_duration_ms'],
                    'cache_path': path.relative_to(ROOT).as_posix(), 'cache_sha256': checksum(path)})
                print(f'DENSE {len(records)}/16 {name}: {len(times)} original observations', flush=True)
            except Exception as error:
                failures.append({'video': row['file'], 'error': repr(error)})
    result = {'schema': 'senpai-dense-reference-extraction/1', 'kind': 'reference-extraction-research-not-human-rep-accuracy',
        'protocol_sha256': checksum(PROTOCOL), 'preprocessing_id': PIPELINE, 'sample_period_ms': 33,
        'maximum_duration_ms': None, 'model_sha256': model_sha, 'videos': sorted(records, key=lambda r: r['video']),
        'failures': failures, 'seconds': time.monotonic()-start, 'new_manual_labels': 0,
        'old_caches_replaced': False, 'source_replay_performed': False, 'release_qualified': False}
    report_path.write_text(json.dumps(result, indent=2)+'\n')
    if failures or len(records) != 16:
        raise RuntimeError('Dense reference extraction incomplete; keep failure report')
    print(json.dumps({k: result[k] for k in ['seconds', 'failures', 'release_qualified']}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workers', type=int, default=3)
    run(parser.parse_args().workers)
