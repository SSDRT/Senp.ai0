import copy
import unittest

from senpqa.live_accuracy import score


class LiveAccuracyTests(unittest.TestCase):
    def sample(self):
        labels = [{"id": i, "x": x, "y": y, "visible": True} for i, x, y in ((11, .2, .2), (13, .2, .4), (15, .4, .4))]
        truth = {"width": 1000, "height": 1000, "annotation_source": "unit-test-known-geometry", "frames": [
            {"timestamp_ms": 0, "person_present": True, "torso_length_px": 200, "landmarks": labels},
            {"timestamp_ms": 100, "person_present": True, "torso_length_px": 200, "landmarks": labels},
            {"timestamp_ms": 200, "person_present": False},
        ]}
        joints = [dict(joint, visibility=.9, presence=.9) for joint in labels]
        prediction = {"frames": [{"timestamp_ms": 0, "status": "VALID", "raw_status": "VALID", "filtered": joints, "raw": joints}]}
        return truth, prediction

    def test_missing_predictions_reduce_pck_instead_of_inflating_accuracy(self):
        truth, prediction = self.sample()
        result = score(truth, prediction)
        self.assertEqual(result["pck_005_all_visible"], .5)
        self.assertEqual(result["joint_coverage"], .5)
        self.assertEqual(result["angle_mae_degrees"], 0.0)
        self.assertEqual(result["missing_prediction_frames"], 2)

    def test_false_valid_and_missing_confidence_are_reported(self):
        truth, prediction = self.sample()
        prediction["frames"].append(dict(copy.deepcopy(prediction["frames"][0]), timestamp_ms=200))
        prediction["frames"][0]["filtered"][0]["presence"] = None
        result = score(truth, prediction)
        self.assertEqual(result["false_valid_rate"], 1.0)
        self.assertEqual(result["accepted_visible_joints"], 2)
        self.assertEqual(result["measured_angles"], 0)

    def test_duplicate_timestamps_and_unattributed_truth_are_rejected(self):
        truth, prediction = self.sample()
        prediction["frames"].append(prediction["frames"][0])
        with self.assertRaises(ValueError):
            score(truth, prediction)
        truth["annotation_source"] = ""
        with self.assertRaises(ValueError):
            score(truth, {"frames": []})
