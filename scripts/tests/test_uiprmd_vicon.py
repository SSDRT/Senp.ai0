import sys
from pathlib import Path
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_vicon import angle, aligned_segment_bounds, measurement_features, MARKERS, INDEX, markers


class ViconAdapterTests(unittest.TestCase):
    def test_known_angles_and_rigid_transform_invariance(self):
        a=np.array([[1.,2,3],[1,2,3]])
        b=np.array([[2.,2,3],[2,2,3]])
        c=np.array([[2.,3,3],[3,2,3]])
        np.testing.assert_allclose(angle(a,b,c),[90,180])
        r=np.array([[0.,-1,0],[1,0,0],[0,0,1]])
        np.testing.assert_allclose(angle(a@r*2+9,b@r*2+9,c@r*2+9),[90,180])

    def test_degenerate_nonfinite_and_missing_markers_are_not_zero_degree_angles(self):
        for a in [[0.,0,0],[float('nan'),1,2],[2,2,2]]:
            self.assertTrue(np.isnan(angle(np.array([a]),np.array([[2.,2,2]]),np.array([[3.,2,2]]))[0]))

    def test_marker_table_is_positions_not_joint_angle_table(self):
        self.assertEqual(39,len(MARKERS))
        self.assertEqual(23,INDEX['LASI'])
        self.assertEqual(28,INDEX['LKNE'])
        self.assertEqual(16,INDEX['RSHO'])
        self.assertEqual(18,INDEX['RELB'])
        with self.assertRaises(ValueError):markers(np.ones((10,66)))

    def test_missing_one_wrist_marker_does_not_synthesize_an_elbow_angle(self):
        points=np.ones((1,39,3))
        points[:,INDEX['RSHO']]=[1,1,1]
        points[:,INDEX['RELB']]=[1,2,1]
        points[:,INDEX['RWRA']]=[2,2,1]
        points[:,INDEX['RWRB']]=[0,0,0]
        self.assertTrue(np.isnan(measurement_features(points.reshape(1,117))['angle.right_elbow'][0]))

    def test_exact_alignment_preserves_continuous_original_time(self):
        full=np.arange(60).reshape(20,3)
        self.assertEqual((3,8),aligned_segment_bounds(full,full[3:9]))
        self.assertEqual((3,8),aligned_segment_bounds(full,full[3:9]+3e-7))
        with self.assertRaises(ValueError):aligned_segment_bounds(full,full[3:9]+.001)
        with self.assertRaises(ValueError):aligned_segment_bounds(np.tile(full,(2,1)),full[3:9])


if __name__=='__main__':unittest.main()
