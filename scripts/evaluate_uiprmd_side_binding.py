"""Compare an explicitly changed research variant; never rewrite fixed-side scores."""
import json
from fetch_uiprmd import DATA, ROOT, checksum
from evaluate_uiprmd_development import evaluate_rows

PROTOCOL = ROOT / 'models/uiprmd-side-binding-protocol.json'
ROW_ID = ('subject', 'movement', 'correct')
SOURCE_KEYS = ('subject', 'movement', 'exercise_id', 'correct', 'side', 'source_member',
               'source_sha256', 'reference_subject', 'reference_sha256', 'reference_key', 'episodes')


def compare(fixed, causal, scope, protocol_hash):
    if scope not in {'development', 'validation'}:
        raise ValueError('Only development and already-opened validation are permitted')
    subjects = {'01', '02', '03', '04'} if scope == 'development' else {'05', '06', '07'}
    expected = {(s, m, c) for s in subjects for m in ['m01', 'm07'] for c in [True, False]}
    for mode, replay in [('fixed', fixed), ('causal', causal)]:
        if (replay['schema'] != 'uiprmd-side-binding-replay/1' or replay['scope'] != scope or
                replay['mapping_mode'] != mode or replay['compiler'] != 'qualified-rep-compiler/5' or
                replay['candidate_protocol_sha256'] != protocol_hash or replay['final_test_trajectories_opened'] or
                replay['measurement_domain'] != 'skeletal' or replay['raw_landmark_network_trained']):
            raise ValueError('Replay identity or split changed')
        keys = [tuple(row[k] for k in ROW_ID) for row in replay['rows']]
        if len(keys) != len(expected) or set(keys) != expected:
            raise ValueError('Incomplete, duplicated or final-test cohort')
        for row in replay['rows']:
            if row['status'] != 'REPLAYED' or len(row['episodes']) != 10:
                raise ValueError('All original recordings and repetitions must remain')
            if row['side'] != ('LEFT' if row['movement'] == 'm01' else 'RIGHT'):
                raise ValueError('Frozen declared-side metadata changed')
            if any(type(e['correct']) is not bool or e['correct'] != row['correct'] for e in row['episodes']):
                raise ValueError('Publisher labels changed')
            reference = '02' if scope == 'development' and row['subject'] == '01' else '01'
            if row['reference_subject'] != reference:
                raise ValueError('Source outcomes cannot select the reference')
    if fixed['manifest_sha256'] != causal['manifest_sha256']:
        raise ValueError('Input exports differ')
    old = {tuple(row[k] for k in ROW_ID): row for row in fixed['rows']}
    bindings = []
    for row in causal['rows']:
        baseline = old[tuple(row[k] for k in ROW_ID)]
        if any(row[k] != baseline[k] for k in SOURCE_KEYS):
            raise ValueError('Source, reference or publisher episode metadata differ')
        if row['movement'] == 'm01':
            if row != baseline:
                raise ValueError('Squat must remain byte-equivalent at the parsed row level')
            continue
        if row['research_variant'] != 'standing-unilateral-shoulder-abduction-interchangeable-side':
            raise ValueError('Changed variant must be explicit')
        locked = [event for event in row['binding_events'] if event['state'] == 'LOCKED']
        if len(locked) > 1 or any(event['side'] is not None for event in row['binding_events'] if event['state'] != 'LOCKED'):
            raise ValueError('No per-rep side selection is permitted')
        if locked:
            event = locked[0]
            if event['side'] not in {'LEFT', 'RIGHT'} or event['locked_ms'] != event['timestamp_ms']:
                raise ValueError('Invalid causal binding')
            if any(attempt['completed_ms'] < event['locked_ms'] for attempt in row['attempts']):
                raise ValueError('A pre-binding count cannot be exposed')
        elif row['attempts']:
            raise ValueError('An unbound side cannot produce attempts')
        bindings.append({k: row[k] for k in ROW_ID} | {'events': row['binding_events']})
    results = {'fixed': evaluate_rows(fixed['rows']), 'causal_variant': evaluate_rows(causal['rows'])}
    total = 160 if scope == 'development' else 120
    for result in results.values():
        if result['counts']['total_reps'] != total or result['counts']['correct_reps'] != total // 2 or result['counts']['incorrect_reps'] != total // 2:
            raise ValueError('Frozen label denominators changed')
    def grouped(replay, key):
        return {value: {k: v for k, v in evaluate_rows([r for r in replay['rows'] if r[key] == value]).items()
                        if k != 'rep_results'} for value in sorted({r[key] for r in replay['rows']})}
    return {'schema': 'uiprmd-side-binding-comparison/1', 'scope': scope,
            'kind': 'changed-variant-skeletal-research-not-frozen-right-side-score-or-camera-precision',
            'results': results, 'per_movement': {mode: grouped(replay, 'movement') for mode, replay in [('fixed', fixed), ('causal_variant', causal)]},
            'per_subject': {mode: grouped(replay, 'subject') for mode, replay in [('fixed', fixed), ('causal_variant', causal)]},
            'binding_traces': bindings, 'readiness_points_earned': 0, 'production_changed': False,
            'frozen_variant_score_replaced': False, 'final_test_subjects_opened': False,
            'uses_already_opened_validation_for_selection': scope == 'validation',
            'candidate_protocol_sha256': protocol_hash}


def main():
    protocol = json.loads(PROTOCOL.read_text())
    for path, digest in protocol['source_sha256'].items():
        if checksum(ROOT / path, 'sha256') != digest:
            raise ValueError(f'Frozen research source changed: {path}')
    for scope in ['development', 'validation']:
        paths = [DATA / f'side-binding-{scope}-{mode}-replay.json' for mode in ['fixed', 'causal']]
        fixed, causal = [json.loads(path.read_text()) for path in paths]
        if checksum(DATA / f'{scope}-vicon/manifest.json', 'sha256') != protocol[f'{scope}_manifest_sha256']:
            raise ValueError('Export manifest changed')
        result = compare(fixed, causal, scope, checksum(PROTOCOL, 'sha256'))
        result['replay_sha256'] = {mode: checksum(path, 'sha256') for mode, path in zip(['fixed', 'causal'], paths)}
        output = DATA / f'side-binding-{scope}-comparison.json'
        output.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
        print(json.dumps({'scope': scope, 'results': {mode: {k: v for k, v in metrics.items() if k != 'rep_results'}
              for mode, metrics in result['results'].items()}, 'per_movement': result['per_movement']}, indent=2))


if __name__ == '__main__':
    main()
