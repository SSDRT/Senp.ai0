import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from analyze_uiprmd_evidence import feature_summary, read_scope


def sample(value, confidence, deviation, returning=False):
    return {'uses_return_branch': returning, 'features': [{'observed_feature': 'angle.left_hip',
            'observed_degrees': value, 'confidence': confidence, 'lower_degrees': 100, 'upper_degrees': 110,
            'acceptable_degrees': 12, 'reject_degrees': 30, 'deviation_degrees': deviation}]}


class EvidenceAnalysisTests(unittest.TestCase):
    def test_missing_values_do_not_enter_disagreement_denominator(self):
        result = feature_summary([sample(140, 1, 30), sample(None, .2, None)])['angle.left_hip']
        self.assertEqual(1, result['missing_or_below_confidence_samples'])
        self.assertEqual(1, result['observed_samples'])
        self.assertEqual(1, result['outside_fraction_of_observed_samples'])
        self.assertEqual(0, result['above_clear_bound_samples_not_confirmed_faults'])

    def test_original_threshold_boundaries_and_return_branch_remain_distinct(self):
        rows = [sample(122, 1, 12), sample(141, 1, 31, True), sample(140, 1, 30)]
        result = feature_summary(rows)['angle.left_hip']
        self.assertEqual(2, result['outside_acceptable_samples'])
        self.assertEqual(1, result['above_clear_bound_samples_not_confirmed_faults'])
        self.assertEqual(1, result['outside_outbound_samples'])
        self.assertEqual(1, result['outside_return_samples'])

    def test_empty_observations_remain_unknown(self):
        self.assertEqual({}, feature_summary([]))
        result = feature_summary([sample(None, 0, None)])['angle.left_hip']
        self.assertIsNone(result['outside_fraction_of_observed_samples'])
        self.assertIsNone(result['median_signed_residual_degrees'])

    def test_final_test_scope_is_rejected_before_any_file_read(self):
        for scope in ['test', 'final', '08', '../validation']:
            with self.assertRaises(PermissionError): read_scope(scope)


if __name__ == '__main__':
    unittest.main()
