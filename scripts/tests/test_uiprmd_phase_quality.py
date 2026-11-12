import sys
from pathlib import Path
import unittest
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from uiprmd_phase_quality import phase_features, verify_development_cohort, REGIONS


def evidence(time, progress, returning=False):
    return dict(timestamp_ms=time, progress=progress, uses_return_branch=returning)


def cohort():
    return [dict(subject=s, movement=m, correct=c, episodes=[{}]*10,
        reference_subject='02' if s == '01' else '01')
        for s in ('01', '02', '03', '04') for m in ('m01', 'm07') for c in (True, False)]


class PhaseQualityTests(unittest.TestCase):
    def test_exact_engine_phase_separates_outbound_and_return_without_source_peak_search(self):
        values = {'x': np.array([10., 20., 30., 40., 50., 60.])}
        samples = [evidence(0, .1), evidence(10, .1001), evidence(20, .85),
                   evidence(30, .1, True), evidence(40, .5, True), evidence(50, 1.3, True)]
        vector, names = phase_features(values, samples, 0, 50)
        for region, expected in zip(REGIONS, (10., 20., 30., 40., 50., 60.)):
            self.assertEqual(expected, vector[names.index('x.'+region+'.median')])
            self.assertEqual(1., vector[names.index('x.'+region+'.observed_fraction')])

    def test_future_geometry_and_labels_do_not_affect_completed_attempt_features(self):
        samples = [dict(evidence(0, .3), correct=False), dict(evidence(10, .4), correct=True)]
        a = phase_features({'x': np.array([10., 20., 999.])}, samples, 0, 10)[0]
        b = phase_features({'x': np.array([10., 20.])}, [evidence(0, .3), evidence(10, .4)], 0, 10)[0]
        np.testing.assert_allclose(a, b, equal_nan=True)

    def test_missing_bins_and_missing_original_markers_are_not_filled(self):
        vector, names = phase_features({'x': np.array([np.nan, 4.])},
            [evidence(0, .4), evidence(10, .5)], 0, 10)
        self.assertEqual(.5, vector[names.index('x.outbound_middle.observed_fraction')])
        self.assertEqual(4., vector[names.index('x.outbound_middle.median')])
        self.assertTrue(np.isnan(vector[names.index('x.return_middle.median')]))
        self.assertEqual(0., vector[names.index('x.return_middle.observed_fraction')])

    def test_duplicate_out_of_attempt_future_nonacquisition_or_invalid_phase_is_rejected(self):
        invalid = [[evidence(0, .4), evidence(0, .5)], [evidence(20, .5)],
                   [evidence(5, .5)], [evidence(0, float('nan'))],
                   [evidence(0, .5, 1)], [evidence(-10, .5)]]
        for samples in invalid:
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                phase_features({'x': np.array([1., 2., 3.])}, samples, 0, 10)

    def test_feature_schema_is_stable_and_empty_evidence_does_not_manufacture_observations(self):
        vector, names = phase_features({'z': np.array([4.]), 'a': np.array([3.])}, [], 0, 10)
        self.assertEqual('a.outbound_ready.median', names[0])
        self.assertEqual(24, len(vector))
        self.assertTrue(np.isnan(vector[::2]).all())
        self.assertTrue((vector[1::2] == 0).all())

    def test_full_scope_checked_before_any_source_reads(self):
        verify_development_cohort(cohort())
        for subject in ('05', '08', '10'):
            rows = cohort(); rows[-1]['subject'] = subject
            with self.assertRaises(PermissionError):
                verify_development_cohort(rows)
        for rows in (cohort()[:-1], cohort()+[cohort()[0]], [cohort()[0]]*16):
            with self.assertRaises(ValueError):
                verify_development_cohort(rows)

    def test_original_reference_and_label_denominators_cannot_be_substituted(self):
        rows = cohort(); rows[0]['reference_subject'] = '01'
        with self.assertRaises(ValueError): verify_development_cohort(rows)
        rows = cohort(); rows[0]['episodes'] = [{}]*9
        with self.assertRaises(ValueError): verify_development_cohort(rows)
        rows = cohort(); rows[0]['correct'] = 1
        with self.assertRaises(ValueError): verify_development_cohort(rows)


if __name__ == '__main__': unittest.main()
