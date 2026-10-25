"""Describe actual comparator bands; never change labels, targets or verdicts."""
import gzip
import json
from collections import Counter, defaultdict
import numpy as np
from fetch_uiprmd import ROOT, DATA, checksum
from evaluate_uiprmd_development import evaluate_rows, match_events, QUALIFIED


def feature_summary(samples):
    features = defaultdict(list)
    for sample in samples:
        for feature in sample['features']:
            features[feature['observed_feature']].append((sample, feature))
    results = {}
    for name, rows in sorted(features.items()):
        observed = [(s, f) for s, f in rows if f['observed_degrees'] is not None]
        outside = [(s, f) for s, f in observed if f['deviation_degrees'] > f['acceptable_degrees']]
        clear = [(s, f) for s, f in observed if f['deviation_degrees'] > f['reject_degrees']]
        residuals = [f['observed_degrees']-(f['lower_degrees']+f['upper_degrees'])/2 for _, f in observed]
        results[name] = {'sample_count': len(rows), 'observed_samples': len(observed),
                         'missing_or_below_confidence_samples': len(rows)-len(observed),
                         'outside_acceptable_samples': len(outside), 'above_clear_bound_samples_not_confirmed_faults': len(clear),
                         'outside_fraction_of_observed_samples': len(outside)/len(observed) if observed else None,
                         'median_signed_residual_degrees': float(np.median(residuals)) if residuals else None,
                         'maximum_deviation_degrees': max((f['deviation_degrees'] for _, f in observed), default=None),
                         'outside_outbound_samples': sum(not s['uses_return_branch'] for s, _ in outside),
                         'outside_return_samples': sum(s['uses_return_branch'] for s, _ in outside)}
    return results


def analyze(records):
    metrics = evaluate_rows(records)
    reps, attempts, disagreement = [], [], Counter()
    for row in records:
        matches, unmatched = match_events(row['episodes'], row['attempts'])
        by_attempt = defaultdict(list)
        for sample in row['samples']:
            by_attempt[sample['attempt_id']].append(sample)
        for episode_index, episode in enumerate(row['episodes']):
            match = matches.get(episode_index)
            attempt = row['attempts'][match['attempt_index']] if match else None
            samples = by_attempt[attempt['attempt_id']] if attempt else []
            features = feature_summary(samples)
            reps.append({'subject': row['subject'], 'movement': row['movement'], 'correct': episode['correct'],
                         'episode': episode['episode'], 'verdict': attempt['verdict'] if attempt else 'NO_EVENT',
                         'maximum_supported_progress': attempt['maximum_progress'] if attempt else None,
                         'engine_visible_fraction': attempt['visible_fraction'] if attempt else None,
                         'engine_ambiguous_fraction': attempt['ambiguous_fraction'] if attempt else None,
                         'engine_reasons': attempt['reasons'] if attempt else [], 'features': features})
            if episode['correct'] and attempt and attempt['verdict'] not in QUALIFIED:
                for reason in attempt['reasons']:
                    disagreement[row['movement']+'/'+reason] += 1
        for i, attempt in enumerate(row['attempts']):
            samples = by_attempt[attempt['attempt_id']]
            attempts.append({'subject': row['subject'], 'movement': row['movement'], 'record_label_correct': row['correct'],
                             **attempt, 'matched_to_original_rep': i not in unmatched, 'features': feature_summary(samples)})
    return {'metrics': metrics, 'good_rep_withholding_reasons_overlap': dict(disagreement),
            'repetitions': reps, 'all_attempts_including_unmatched': attempts}


def read_scope(scope):
    if scope not in {'development', 'validation'}:
        raise PermissionError('Final-test trajectories are forbidden')
    directory = DATA/f'reference-evidence-audit-{scope}'
    manifest_path = directory/'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if (manifest['schema'] != 'uiprmd-reference-evidence-audit/1' or manifest['scope'] != scope or
            manifest['compiler'] != 'qualified-rep-compiler/5' or manifest['measurement_domain'] != 'skeletal' or
            manifest['final_test_trajectories_opened'] or manifest['production_acceptance_policy_changed']):
        raise ValueError('Diagnostic identity changed')
    subjects = ['01', '02', '03', '04'] if scope == 'development' else ['05', '06', '07']
    expected = {(s, m, c) for s in subjects for m in ['m01', 'm07'] for c in [True, False]}
    keys = [(r['subject'], r['movement'], r['correct']) for r in manifest['records']]
    if len(keys) != len(expected) or set(keys) != expected:
        raise ValueError('Incomplete, duplicated or final-test cohort')
    if checksum(DATA/f'{scope}-vicon/manifest.json', 'sha256') != manifest['source_manifest_sha256']:
        raise ValueError('Source export changed')
    if checksum(DATA/'development-vicon/manifest.json', 'sha256') != manifest['reference_manifest_sha256']:
        raise ValueError('Reference export changed')
    records = []
    for entry in manifest['records']:
        path = directory/entry['file']
        if path.resolve().parent != directory.resolve() or checksum(path, 'sha256') != entry['sha256']:
            raise ValueError('Diagnostic file identity changed')
        with gzip.open(path, 'rt') as stream:
            record = json.load(stream)
        if (record['scope'] != scope or not record['comparison_outputs_identical_with_and_without_observer'] or
                any(record[k] != entry[k] for k in ['subject', 'movement', 'correct']) or len(record['episodes']) != 10):
            raise ValueError('Diagnostic cohort or observer parity changed')
        records.append(record)
    return manifest_path, records


def main():
    for scope in ['development', 'validation']:
        manifest, records = read_scope(scope)
        result = analyze(records)
        total = 160 if scope == 'development' else 120
        if result['metrics']['counts']['total_reps'] != total:
            raise ValueError('Frozen label denominator changed')
        baseline = json.loads((DATA/f'side-binding-{scope}-fixed-replay.json').read_text())
        previous = evaluate_rows(baseline['rows'])
        if result['metrics'] != previous:
            raise ValueError('Acceptance outcomes differ from the prior fixed-side baseline')
        result.update(schema='uiprmd-reference-evidence-analysis/1', scope=scope,
                      kind='diagnostic-reference-disagreement-not-new-fault-labels-or-live-camera-accuracy',
                      manifest_sha256=checksum(manifest, 'sha256'), original_label_denominators_retained=True,
                      comparison_metrics_equal_prior_fixed_side=True, readiness_points_earned=0,
                      final_test_subjects_opened=False,
                      limitations=['Sample fractions are not duration-weighted engine ambiguity fractions.',
                                   'Deviation beyond a bound is not a confirmed fault without the original dwell logic.',
                                   'Publisher correct labels describe a clinical exercise, not exact imitation of one upload.',
                                   'Binary marker observability is not MP33 camera confidence.'])
        output = DATA/f'reference-evidence-analysis-{scope}.json'
        output.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
        print(json.dumps({'scope': scope, 'metrics': {k: v for k, v in result['metrics'].items() if k != 'rep_results'},
                          'withheld_good_reasons': result['good_rep_withholding_reasons_overlap']}, indent=2))


if __name__ == '__main__':
    main()
