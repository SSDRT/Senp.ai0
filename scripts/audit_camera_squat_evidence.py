"""One original MP33 squat replay: disagreement diagnostics, no per-rep truth."""
import json
from collections import Counter
from fetch_uiprmd import ROOT, checksum
from analyze_uiprmd_evidence import feature_summary


def main():
    data = ROOT/'test-artifacts/exercise-knowledge'
    name = 'subject_018_squat_good_front-left.json'
    current_path = data/'evidence-audit-complete-full-standard'/name
    previous_path = data/'mirror-calibration-complete-full-standard'/name
    current, previous = [json.loads(path.read_text()) for path in [current_path, previous_path]]
    for key in ['video_sha256', 'reference_video_sha256', 'reference_key', 'exercise', 'side', 'view',
                'counts', 'attempts', 'faults', 'frame_count', 'reference_frame_count']:
        if current[key] != previous[key]:
            raise ValueError('Diagnostic engine changed frozen camera evidence: '+key)
    attempts, all_samples = [], []
    for attempt in current['attempts']:
        samples = [row['evidence'] for row in current['trace'] if row.get('evidence') is not None and
                   row['evidence']['attempt_id'] == attempt['attempt_id']]
        if any(s['timestamp_ms'] < attempt['started_ms'] or s['timestamp_ms'] > attempt['completed_ms'] for s in samples):
            raise ValueError('Diagnostic sample outside causal attempt')
        all_samples.extend(samples)
        features = feature_summary(samples)
        examples = []
        for feature in features:
            rows = [(s, f) for s in samples for f in s['features'] if f['observed_feature'] == feature and f['deviation_degrees'] is not None]
            if rows:
                s, f = max(rows, key=lambda item: item[1]['deviation_degrees'])
                examples.append({'feature': feature, 'timestamp_ms': s['timestamp_ms'], 'actual_degrees': f['observed_degrees'],
                                 'reference_lower': f['lower_degrees'], 'reference_upper': f['upper_degrees'],
                                 'acceptable_allowance': f['acceptable_degrees'], 'deviation_degrees': f['deviation_degrees']})
        attempts.append(attempt | {'features': features, 'largest_observed_disagreements': examples})
    result = {'schema': 'mp33-squat-reference-evidence-audit/1',
              'kind': 'original-camera-replay-diagnostic-not-human-rep-labels-or-precision',
              'video': current['video'], 'reference_video': current['reference_video'],
              'video_sha256': current['video_sha256'], 'reference_video_sha256': current['reference_video_sha256'],
              'source_trace_sha256': checksum(current_path, 'sha256'), 'previous_trace_sha256': checksum(previous_path, 'sha256'),
              'counts_unchanged': current['counts'], 'attempts': attempts,
              'attempt_reasons_overlap': dict(Counter(reason for a in attempts for reason in a['reasons'])),
              'feature_summary_active_samples': feature_summary(all_samples),
              'weak_whole_clip_label_is_not_per_rep_truth': True, 'actual_human_rep_precision': None,
              'raw_mp33_modified': False, 'thresholds_changed': False, 'readiness_points_earned': 0}
    output = data/'camera-squat-evidence-audit.json'
    output.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
    print(json.dumps({k: result[k] for k in ['counts_unchanged', 'attempt_reasons_overlap', 'feature_summary_active_samples']}, indent=2))


if __name__ == '__main__':
    main()
