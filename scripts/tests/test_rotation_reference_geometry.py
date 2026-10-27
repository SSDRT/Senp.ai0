import copy
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_rotation_reference_candidates import rotate_rgb, map_to_original, validate_protocol
from extract_exercise_quality_features import geometry


class RotationReferenceGeometryTests(unittest.TestCase):
    def test_exact_rgb_quarter_turn_retains_pixels_without_resampling(self):
        pixels=np.arange(2*3*3,dtype=np.uint8).reshape(2,3,3);before=pixels.copy()
        for turn in (90,270):
            actual=rotate_rgb(pixels,turn)
            self.assertEqual((3,2,3),actual.shape)
            self.assertTrue(actual.flags.c_contiguous)
            for y in range(2):
                for x in range(3):
                    location=(x,1-y) if turn==90 else (2-x,y)
                    np.testing.assert_array_equal(actual[location],pixels[y,x])
            np.testing.assert_array_equal(pixels,before)

    def test_inverse_mapping_non_square_image_depth_confidence_and_world_axes(self):
        p=[.2,.7,-.3,.83,.91,2.,-3.,4.];raw=[p.copy() for _ in range(33)]
        before=copy.deepcopy(raw)
        np.testing.assert_allclose(map_to_original(raw,90,800,400)[0],[.7,.8,-.15,.83,.91,-3.,-2.,4.])
        np.testing.assert_allclose(map_to_original(raw,270,800,400)[0],[.3,.2,-.15,.83,.91,3.,2.,4.])
        self.assertEqual(before,raw)

    def test_inverse_rigid_world_rotation_preserves_all_geometry_and_landmark_order(self):
        rng=np.random.default_rng(17)
        raw=[[.3,.4,.1,.95,.9,*point] for point in rng.normal(size=(33,3))]
        expected=geometry([p[5:8] for p in raw],[.9]*33)
        for turn in (90,270):
            mapped=map_to_original(raw,turn,960,540)
            actual=geometry([p[5:8] for p in mapped],[.9]*33)
            np.testing.assert_allclose(actual,expected,atol=1e-10)
            for p,q in zip(raw,mapped):
                self.assertEqual(p[3:5],q[3:5]);self.assertEqual(p[7],q[7])

    def test_out_of_image_predictions_are_retained_without_clamping_or_confidence_change(self):
        raw=[[-.2,1.4,-3.,.3,.4,7.,8.,9.] for _ in range(33)]
        mapped=map_to_original(raw,90,640,480)
        self.assertEqual(mapped[0][:2],[1.4,1.2]);self.assertEqual(mapped[0][3:5],[.3,.4])
        self.assertEqual([],map_to_original([],90,640,480))

    def test_malformed_raw_points_or_unsupported_angles_fail_closed(self):
        raw=[[.5]*8 for _ in range(33)]
        for turn in (0,45,180,360):
            with self.assertRaises(ValueError):map_to_original(raw,turn,640,480)
            with self.assertRaises(ValueError):rotate_rgb(np.zeros((2,3,3)),turn)
        for bad,w,h in ((raw[:32],640,480),([p[:7] for p in raw],640,480),(raw,0,480),(raw,640,-1)):
            with self.assertRaises(ValueError):map_to_original(bad,90,w,h)
        raw[0][5]=float('nan')
        with self.assertRaises(ValueError):map_to_original(raw,90,640,480)

    def test_scope_violation_rejected_before_manifest_or_other_data_access(self):
        spec=dict(schema='senpai-rotation-reference-protocol/1',reference_only=True,allow_final_test_access=False,
            sample_period_ms=66,maximum_duration_ms=None,clockwise_degrees=[90,270],coarse_minimum_confidence=.65,
            long_edge_cap_px=960,same_subject_torso_fraction=.5,same_subject_floor_normalized=.05)
        for key,value in [('reference_only',False),('allow_final_test_access',True),('clockwise_degrees',[90]),
            ('coarse_minimum_confidence',.5),('maximum_duration_ms',24000),('sample_period_ms',33)]:
            invalid=dict(spec);invalid[key]=value
            with self.assertRaises(ValueError):validate_protocol(invalid)


if __name__=='__main__':unittest.main()
