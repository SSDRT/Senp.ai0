import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import json
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from uiprmd_reference_plane_guard import fit_reference, evaluate_attempt, bin_key
from evaluate_reference_plane_guard import apply_guard, frozen_protocol


def reference():
    band = {'lower': 0., 'upper': 3., 'acceptable': 12., 'reject': 30.}
    return {'status': 'READY', 'bands': {f'{b}-{i}': dict(band) for b in ['outbound', 'return'] for i in range(4)}}


def samples(times):
    return [{'timestamp_ms': t, 'attempt_id': 1, 'progress': .5, 'uses_return_branch': False} for t in times]


class ReferencePlaneGuardTests(unittest.TestCase):
    def test_clear_deviation_needs_original_dwell_and_remains_latched_after_recovery(self):
        x = np.full(80, 60.)
        self.assertIsNone(evaluate_attempt(samples([0, 50, 100]), x, reference())['fault'])
        result = evaluate_attempt(samples([0, 80, 160]), x, reference())
        self.assertEqual(160, result['fault']['confirmed_ms'])
        x[17:] = 0
        recovered = evaluate_attempt(samples([0, 80, 160, 200, 300]), x, reference())
        self.assertEqual(result['fault'], recovered['fault'])

    def test_gaps_missing_and_duplicate_timestamps_cannot_create_supported_faults(self):
        x = np.full(100, 60.)
        self.assertIsNone(evaluate_attempt(samples([0, 300]), x, reference())['fault'])
        x[8] = np.nan
        self.assertIsNone(evaluate_attempt(samples([0, 80, 160]), x, reference())['fault'])
        with self.assertRaises(ValueError): evaluate_attempt(samples([0, 0]), x, reference())
        with self.assertRaises(ValueError): evaluate_attempt(samples([1, 10]), x, reference())
        missing = evaluate_attempt(samples([0, 80, 160]), np.full(20, np.nan), reference())
        self.assertEqual('UNASSESSABLE', missing['status'])

    def test_reference_requires_three_distinct_self_cycles_per_phase(self):
        rows = []; values = np.zeros(240)
        for cycle in range(3):
            for branch in [False, True]:
                for i in range(4):
                    for _ in range(5):
                        rows.append({'timestamp_ms': len(rows)*10, 'attempt_id': cycle,
                                     'progress': (i+.5)/4, 'uses_return_branch': branch})
        self.assertEqual('READY', fit_reference(rows, values)['status'])
        for row in rows: row['attempt_id'] = 1
        self.assertEqual('REFERENCE_UNAVAILABLE', fit_reference(rows, values)['status'])

    def test_prefix_and_phase_do_not_use_future_source_values(self):
        rows = samples([0, 80, 160]); x = np.zeros(50)
        first = evaluate_attempt(rows, x, reference())
        x[17:] = 80
        self.assertEqual(first, evaluate_attempt(rows, x, reference()))
        self.assertEqual('outbound-3', bin_key({'progress': 1.2, 'uses_return_branch': False}))
        self.assertEqual('return-0', bin_key({'progress': -.1, 'uses_return_branch': True}))

    def test_original_clear_bound_is_strict_and_reference_failure_never_passes(self):
        x = np.full(30, 33.)
        grey = evaluate_attempt(samples([0, 80, 160]), x, reference())
        self.assertIsNone(grey['fault'])
        self.assertEqual('AMBIGUOUS_PLANE_MATCH', grey['reason'])
        self.assertEqual('ASSESSED', evaluate_attempt(samples([0, 80, 160]), np.zeros(30), reference())['status'])
        self.assertEqual('UNASSESSABLE', evaluate_attempt([], x, {'status': 'REFERENCE_UNAVAILABLE', 'reason': 'missing'})['status'])

    def test_additional_guard_cannot_upgrade_an_unknown_rejected_or_missing_attempt(self):
        for verdict in ['UNASSESSABLE', 'REJECTED']:
            attempt = {'verdict': verdict, 'reasons': ['original']}
            self.assertEqual(attempt, apply_guard(attempt, {'status': 'ASSESSED'}))
            self.assertEqual(attempt, apply_guard(attempt, {'status': 'REJECTED'}))
        attempt = {'verdict': 'ACCEPTABLE', 'reasons': []}
        result = apply_guard(attempt, {'status': 'UNASSESSABLE', 'reason': 'missing'})
        self.assertEqual('UNASSESSABLE', result['verdict'])
        self.assertEqual('ACCEPTABLE', attempt['verdict'])

    def test_rejected_development_candidate_stops_before_git_or_validation_access(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'protocol.json'
            path.write_text(json.dumps({'validation_evaluation_authorized': False}))
            with patch('evaluate_reference_plane_guard.PROTOCOL', path), patch('evaluate_reference_plane_guard.subprocess.check_output') as git:
                with self.assertRaises(PermissionError): frozen_protocol()
                git.assert_not_called()


if __name__ == '__main__':
    unittest.main()
