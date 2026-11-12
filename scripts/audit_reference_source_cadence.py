"""Preflight original presentation timestamps before spending inference on denser sampling.

Reads only the frozen camera-reference cohort. Optional dense comparison preserves
both sets of raw coordinates and reports input equivalence, never human accuracy.
"""
import argparse
import gzip
import json
import subprocess

from extract_exercise_quality_features import ROOT, DATA
from extract_dense_reference_candidate import PROTOCOL, checksum, validate_protocol


def timestamp_availability(original, sampled):
    for name, times in [('original', original), ('sampled', sampled)]:
        if not times or any(type(t) is not int for t in times) or any(b <= a for a, b in zip(times, times[1:])):
            raise ValueError('Expected strictly increasing actual '+name+' timestamps')
    if not set(sampled) <= set(original):
        raise ValueError('Cached samples are not original presentation timestamps')
    missing = sorted(set(original)-set(sampled))
    return {'original_frames': len(original), 'cached_observations': len(sampled),
        'unobserved_original_frames': len(missing), 'all_original_frames_already_sampled': not missing,
        'first_original_pts_ms': original[0], 'last_original_pts_ms': original[-1]}


def compare_raw(old, dense):
    if old['source_video_sha256'] != dense['source_video_sha256'] or old['model_sha256'] != dense['model_sha256']:
        raise ValueError('Source/model identities differ')
    a, b = old['frames'], dense['frames']
    same_pts = [r['timestamp_ms'] for r in a] == [r['timestamp_ms'] for r in b]
    if not same_pts:
        return {'same_original_pts': False, 'same_raw_mp33': False, 'all_frame_fields_equal_except_timing': False}
    return {'same_original_pts': True, 'same_raw_mp33': all(x['landmarks'] == y['landmarks'] for x, y in zip(a, b)),
        'all_frame_fields_equal_except_timing': all(
            {k: v for k, v in x.items() if k != 'research_inference_ms'} ==
            {k: v for k, v in y.items() if k != 'research_inference_ms'} for x, y in zip(a, b))}


def read_cache(path):
    with gzip.open(path, 'rt') as stream:
        return json.load(stream)


def run(compare_dense):
    protocol = json.loads(PROTOCOL.read_text())
    manifest_path = ROOT/protocol['manifest_path']
    if checksum(manifest_path) != protocol['manifest_sha256']:
        raise ValueError('Original reference manifest changed')
    selected = validate_protocol(protocol, json.loads(manifest_path.read_text()))
    output = DATA/('dense-reference-cadence-comparison.json' if compare_dense else 'reference-cadence-preflight.json')
    if output.exists():
        raise FileExistsError('Preserve the completed cadence audit')
    rows = []
    for entry in selected:
        source = DATA/'videos-v3'/entry['file']
        if checksum(source) != entry['sha256']:
            raise ValueError('Source video changed')
        old_path = DATA/'complete-reference-full-cache'/(source.stem+'.json.gz')
        old = read_cache(old_path)
        if old['source_video_sha256'] != entry['sha256'] or old['maximum_sampled_duration_ms'] is not None:
            raise ValueError('Expected complete original reference cache')
        probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
            '-show_frames', '-show_entries', 'frame=best_effort_timestamp_time:stream=r_frame_rate,avg_frame_rate',
            '-of', 'json', str(source)], text=True))
        original = [round(float(f['best_effort_timestamp_time'])*1000) for f in probe['frames']]
        availability = timestamp_availability(original, [f['timestamp_ms'] for f in old['frames']])
        row = {'video': source.name, 'source_sha256': entry['sha256'], 'old_cache_sha256': checksum(old_path),
            'publisher_stream_rates': probe['streams'], **availability}
        if compare_dense:
            dense_path = DATA/'dense-reference-full-cache'/(source.stem+'.json.gz')
            row['dense_cache_sha256'] = checksum(dense_path)
            row['dense_comparison'] = compare_raw(old, read_cache(dense_path))
        rows.append(row)
    result = {'schema': 'senpai-original-reference-cadence-audit/1', 'protocol_sha256': checksum(PROTOCOL),
        'kind': 'source-frame-availability-not-rep-precision', 'rows': rows,
        'reference_count': len(rows), 'unobserved_original_frame_total': sum(r['unobserved_original_frames'] for r in rows),
        'all_sources_already_fully_sampled': all(r['all_original_frames_already_sampled'] for r in rows),
        'dense_comparison_performed': compare_dense, 'model_inference_run_by_this_audit': False,
        'synthesis_or_interpolation_performed': False, 'new_human_labels': 0}
    output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k: result[k] for k in ['reference_count', 'unobserved_original_frame_total', 'all_sources_already_fully_sampled']}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare-dense', action='store_true')
    run(parser.parse_args().compare_dense)
