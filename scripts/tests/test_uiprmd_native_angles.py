import sys,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_native_angles import *


class NativeAnglesTests(unittest.TestCase):
    def test_angle_order_does_not_reuse_marker_table(self):
        a=np.arange(234,dtype=float).reshape(2,117)
        self.assertEqual(len(JOINTS),39);self.assertEqual(INDEX['right_shoulder'],28);self.assertEqual(INDEX['left_knee'],17)
        np.testing.assert_array_equal(component(a,'right_shoulder',1),[85,202])
        np.testing.assert_array_equal(component(a,'left_knee',0),[51,168])

    def test_neutral_zero_is_preserved_and_numeric_mask_is_not_confidence(self):
        a=np.zeros((3,117));a[1,5]=np.nan;a[2,9]=np.inf;before=a.copy();t=triplets(a);mask=numeric_mask(a)
        self.assertTrue(mask[0].all());self.assertFalse(mask[1,1,2]);self.assertFalse(mask[2,3,0]);self.assertEqual(t[0,28,1],0)
        np.testing.assert_array_equal(a,before);t[0,0,0]=99;self.assertEqual(a[0,0],0)
        with self.assertRaises(PermissionError):as_camera_abc_features(a)

    def test_holdouts_wrong_sensor_kind_and_path_are_refused_before_io(self):
        class Archive:
            def read(self,name):raise AssertionError('Forbidden archive read')
        for archive,name in [
            ('Movements.zip','Movements/Vicon/Angles/m07_s08_angles.txt'),
            ('Movements.zip','Movements/Vicon/Angles/m01_s05_angles.txt'),
            ('Movements.zip','Movements/Kinect/Angles/m01_s01_angles.txt'),
            ('Movements.zip','Movements/Vicon/Positions/m01_s01_positions.txt'),
            ('Movements.zip','Movements/../Vicon/Angles/m01_s01_angles.txt'),
            ('Incorrect Movements.zip','Incorrect Movements/Vicon/Angles/m01_s01_angles.txt'),
            ('Segmented Movements.zip','Segmented Movements/Vicon/Angles/m01_s01_e00_angles.txt'),
            ('Movements.zip','Movements/Vicon/Angles/m02_s01_angles.txt')]:
            with self.subTest(name=name),self.assertRaises(PermissionError):read_development_member(Archive(),archive,name)
        self.assertEqual(validate_development_member('Incorrect Segmented Movements.zip','Incorrect Segmented Movements/Vicon/Angles/m07_s04_e10_angles_inc.txt')['subject'],'04')

    def test_parsing_never_treats_header_or_wrong_column_count_as_angular_data(self):
        for delimiter in [' ', ',']:
            raw=(delimiter.join(str(i) for i in range(117))+'\n').encode();self.assertEqual(parse_native_angles(raw).shape,(1,117))
        for raw in [b'1 2 3\n',b'joint,data\n']:
            with self.assertRaises(ValueError):parse_native_angles(raw)

    def test_angular_excerpt_alignment_is_absolute_degree_bound_not_time_warp(self):
        full=np.arange(1170,dtype=float).reshape(10,117)*.01;segment=full[3:6].copy();segment[1,5]+=5e-7
        self.assertEqual(aligned_angle_bounds(full,segment),(3,5))
        segment[1,5]+=2e-6
        with self.assertRaises(ValueError):aligned_angle_bounds(full,segment)
        with self.assertRaises(ValueError):aligned_angle_bounds(np.tile(full,(2,1)),full[3:6])


if __name__=='__main__':unittest.main()
