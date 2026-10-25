"""Research-only additional reference-relative arm-plane evidence, never repaired poses."""
import numpy as np

SCHEMA = 'uiprmd-reference-arm-plane-guard/2'
POLICY = {'minimum_reference_cycles': 3, 'minimum_bin_samples': 5, 'maximum_reference_mad_degrees': 12.,
          'maximum_reference_width_degrees': 60., 'acceptable_allowance_degrees': 12.,
          'reject_allowance_degrees': 30., 'fault_confirmation_ms': 160, 'maximum_gap_ms': 250,
          'minimum_visible_fraction': .90, 'maximum_ambiguous_fraction': .10, 'progress_bins': 4}


def bin_key(sample):
    progress = sample['progress']
    if not np.isfinite(progress) or type(sample['uses_return_branch']) is not bool:
        raise ValueError('Finite causal driver progress and explicit branch required')
    index = min(3, max(0, int(np.floor(np.clip(progress, 0, 1)*4))))
    return ('return' if sample['uses_return_branch'] else 'outbound')+'-'+str(index)


def fit_reference(samples, plane_degrees):
    groups = {f'{branch}-{i}': [] for branch in ['outbound', 'return'] for i in range(4)}
    for sample in samples:
        timestamp = sample['timestamp_ms']
        if type(timestamp) is not int or timestamp % 10 or not 0 <= timestamp//10 < len(plane_degrees):
            raise ValueError('Reference samples must retain their original 100 Hz acquisition times')
        value = plane_degrees[timestamp//10]
        if np.isfinite(value) and 0 <= value <= 90:
            groups[bin_key(sample)].append((sample['attempt_id'], float(value)))
    bands = {}
    for key, rows in groups.items():
        cycles = len({a for a, _ in rows})
        if cycles < POLICY['minimum_reference_cycles'] or len(rows) < POLICY['minimum_bin_samples']:
            return {'status': 'REFERENCE_UNAVAILABLE', 'reason': 'INSUFFICIENT_REFERENCE_PHASE_EVIDENCE', 'phase': key}
        values = np.asarray([v for _, v in rows]); median = float(np.median(values))
        mad = float(np.median(np.abs(values-median)))
        lower, upper = map(float, np.quantile(values, [.10, .90]))
        if mad > POLICY['maximum_reference_mad_degrees'] or upper-lower > POLICY['maximum_reference_width_degrees']:
            return {'status': 'REFERENCE_UNAVAILABLE', 'reason': 'NOISY_REFERENCE_PLANE', 'phase': key}
        acceptable = max(POLICY['acceptable_allowance_degrees'], 1.4826*mad*2)
        bands[key] = {'lower': lower, 'upper': upper, 'mad': mad, 'cycles': cycles, 'samples': len(rows),
                      'acceptable': acceptable, 'reject': max(POLICY['reject_allowance_degrees'], acceptable*1.75)}
    return {'status': 'READY', 'schema': SCHEMA, 'bands': bands}


def evaluate_attempt(samples, plane_degrees, reference):
    """No publisher labels, episode bounds, counterpart arm or future poses are inputs."""
    if reference['status'] != 'READY':
        return {'status': 'UNASSESSABLE', 'reason': reference['reason'], 'fault': None}
    previous_ms = None; previous_visible = previous_ambiguous = False
    total_ms = visible_ms = ambiguous_ms = 0
    pending = None; fault = None; rows = []
    for sample in samples:
        now = sample['timestamp_ms']
        if type(now) is not int or now % 10 or not 0 <= now//10 < len(plane_degrees):
            raise ValueError('Source sample lies outside its original acquisition timebase')
        if previous_ms is not None and now <= previous_ms:
            raise ValueError('Source timestamps must strictly increase')
        gap = 0 if previous_ms is None else now-previous_ms
        total_ms += gap
        key = bin_key(sample); band = reference['bands'][key]
        value = float(plane_degrees[now//10]); visible = np.isfinite(value) and 0 <= value <= 90
        if visible and previous_visible and gap <= POLICY['maximum_gap_ms']:
            visible_ms += gap
        if gap > POLICY['maximum_gap_ms']:
            pending = None
        deviation = max(band['lower']-value, value-band['upper'], 0) if visible else None
        ambiguous = visible and deviation > band['acceptable']
        if ambiguous or previous_ambiguous:
            ambiguous_ms += gap
        direction = -1 if visible and value < band['lower'] else 1
        if visible and deviation > band['reject']:
            if pending is None or pending['direction'] != direction:
                pending = {'since': now, 'observations': 0, 'direction': direction}
            pending['observations'] += 1
            if fault is None and pending['observations'] >= 2 and now-pending['since'] >= POLICY['fault_confirmation_ms']:
                fault = {'reason': 'REFERENCE_ARM_PLANE_DEVIATION', 'feature': 'angle.right_arm_out_of_torso_plane',
                         'first_ms': pending['since'], 'confirmed_ms': now, 'deviation_degrees': deviation}
        else:
            pending = None
        rows.append({'timestamp_ms': now, 'phase': key, 'raw_plane_degrees': value if visible else None,
                     'reference_lower': band['lower'], 'reference_upper': band['upper'],
                     'acceptable': band['acceptable'], 'reject': band['reject'], 'deviation_degrees': deviation})
        previous_ms = now; previous_visible = visible; previous_ambiguous = ambiguous
    coverage = visible_ms/total_ms if total_ms else 0
    ambiguity = ambiguous_ms/total_ms if total_ms else 0
    reason = ('REFERENCE_ARM_PLANE_DEVIATION' if fault else 'MISSING_PLANE_EVIDENCE' if coverage < POLICY['minimum_visible_fraction']
              else 'AMBIGUOUS_PLANE_MATCH' if ambiguity > POLICY['maximum_ambiguous_fraction'] else None)
    return {'status': 'REJECTED' if fault else 'UNASSESSABLE' if reason else 'ASSESSED',
            'reason': reason, 'fault': fault, 'visible_fraction': coverage, 'ambiguous_fraction': ambiguity, 'samples': rows}
