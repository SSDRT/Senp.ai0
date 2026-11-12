"""Research-only completed-attempt features from the engine's causal phase.

Whole-rep labels never enter this module. No count, raw marker, reference range
or engine decision is changed. Missing phase/geometry stays missing.
"""
import numpy as np

SCHEMA = 'uiprmd-vicon39-causal-phase-quality/1'
REGIONS = ('outbound_ready', 'outbound_middle', 'outbound_far',
           'return_ready', 'return_middle', 'return_far')


def phase_features(values, evidence, started_ms, completed_ms):
    if (type(started_ms) is not int or type(completed_ms) is not int or
            started_ms < 0 or completed_ms <= started_ms):
        raise ValueError('Original positive-duration attempt required')
    lengths = {len(v) for v in values.values()}
    if not values or len(lengths) != 1:
        raise ValueError('One original recording timebase required')
    regions = [[] for _ in REGIONS]
    previous = -1
    for sample in evidence:
        time = sample['timestamp_ms']
        progress = sample['progress']
        returning = sample['uses_return_branch']
        if (type(time) is not int or time <= previous or time % 10 or
                not started_ms <= time <= completed_ms or time // 10 >= next(iter(lengths)) or
                type(returning) is not bool or type(progress) not in (int, float) or not np.isfinite(progress)):
            raise ValueError('Phase must be an original causal engine observation in this attempt')
        previous = time
        region = 0 if progress <= .10 else 2 if progress >= .85 else 1
        regions[region + (3 if returning else 0)].append(time // 10)
    vector, names = [], []
    for name in sorted(values):
        series = np.asarray(values[name], dtype=float)
        if series.ndim != 1:
            raise ValueError('Scalar original geometry required')
        for region, indexes in zip(REGIONS, regions):
            observed = series[indexes]
            finite = observed[np.isfinite(observed)]
            vector.extend([float(np.median(finite)) if len(finite) else np.nan,
                           len(finite) / len(indexes) if indexes else 0.0])
            names.extend([name + '.' + region + '.median', name + '.' + region + '.observed_fraction'])
    return np.asarray(vector), names


def verify_development_cohort(rows):
    # Check the whole cohort before any source member is read.
    expected = {(s, m, c) for s in ('01', '02', '03', '04')
                for m in ('m01', 'm07') for c in (True, False)}
    keys = [(r['subject'], r['movement'], r['correct']) for r in rows]
    if any(r['subject'] not in {'01', '02', '03', '04'} for r in rows):
        raise PermissionError('This experiment cannot read validation or final subjects')
    if len(keys) != len(expected) or set(keys) != expected:
        raise ValueError('Complete unique frozen development cohort required')
    for row in rows:
        if type(row['correct']) is not bool or len(row['episodes']) != 10:
            raise ValueError('Original ten-rep boolean-labelled recordings required')
        expected_reference = '02' if row['subject'] == '01' else '01'
        if row['reference_subject'] != expected_reference:
            raise ValueError('Frozen cross-person reference changed')
