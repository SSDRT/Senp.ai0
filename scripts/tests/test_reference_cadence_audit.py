import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_reference_source_cadence import compare_raw, timestamp_availability


class ReferenceCadenceAuditTests(unittest.TestCase):
    def test_sampling_faster_cannot_add_frames_to_an_already_complete_source(self):
        result = timestamp_availability([0, 67, 133, 200], [0, 67, 133, 200])
        self.assertTrue(result['all_original_frames_already_sampled'])
        self.assertEqual(result['unobserved_original_frames'], 0)

    def test_available_unsampled_frames_are_reported_without_inventing_any(self):
        result = timestamp_availability([0, 33, 67, 100], [0, 67])
        self.assertFalse(result['all_original_frames_already_sampled'])
        self.assertEqual(result['unobserved_original_frames'], 2)

    def test_nonoriginal_duplicate_reordered_and_empty_timestamps_are_refused(self):
        for original, sampled in [([0, 67], [0, 33]), ([0, 67], [0, 0]), ([67, 0], [0]), ([], [])]:
            with self.assertRaises(ValueError):
                timestamp_availability(original, sampled)

    def test_raw_comparison_ignores_timing_but_detects_changed_confidence_coordinates_and_pts(self):
        old = {'source_video_sha256': 'source', 'model_sha256': 'model',
            'frames': [{'timestamp_ms': 0, 'landmarks': [[.1,.2,.3,.9,.8,1.,2.,3.]], 'research_inference_ms': 10.}]}
        other = copy.deepcopy(old)
        other['frames'][0]['research_inference_ms'] = 20.
        self.assertTrue(compare_raw(old, other)['all_frame_fields_equal_except_timing'])
        for index in [0, 3, 7]:
            other = copy.deepcopy(old)
            other['frames'][0]['landmarks'][0][index] += .01
            self.assertFalse(compare_raw(old, other)['same_raw_mp33'])
        other = copy.deepcopy(old)
        other['frames'][0]['timestamp_ms'] = 33
        self.assertFalse(compare_raw(old, other)['same_original_pts'])
        other['model_sha256'] = 'different'
        with self.assertRaises(ValueError):
            compare_raw(old, other)


if __name__ == '__main__':
    unittest.main()
