import sys
from pathlib import Path
import unittest
from unittest.mock import Mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from inspect_uiprmd_development import read_development_member,parse_publisher_array


class SplitGuardTests(unittest.TestCase):
    def test_held_out_trajectory_is_refused_before_archive_read(self):
        archive=Mock()
        for subject in ['05','08','09','10']:
            with self.assertRaises(PermissionError):
                read_development_member(archive,f'Kinect/Positions/m01_s{subject}_e01_positions.txt',{'01','02','03','04'})
        archive.read.assert_not_called()

    def test_development_file_is_read_without_changing_its_bytes(self):
        archive=Mock();archive.read.return_value=b'original data'
        self.assertEqual(b'original data',read_development_member(archive,'Kinect/Positions/m01_s01_e01_positions.txt',{'01'}))

    def test_traversal_cannot_bypass_the_split(self):
        archive=Mock()
        with self.assertRaises(PermissionError):read_development_member(archive,'../m01_s01_e01_positions.txt',{'01'})
        archive.read.assert_not_called()

    def test_publisher_comma_and_whitespace_encodings_preserve_all_values(self):
        for sensor,columns,separator in [('Kinect',66,','),('Vicon',117,'   ')]:
            raw=(separator.join(str(i/10) for i in range(columns))+'\n').encode()
            array,delimiter=parse_publisher_array(raw,sensor)
            self.assertEqual((1,columns),array.shape)
            self.assertEqual((columns-1)/10,array[0,-1])
            self.assertEqual('comma' if separator==',' else 'whitespace',delimiter)

    def test_wrong_joint_count_is_reported_without_padding_missing_joints(self):
        with self.assertRaisesRegex(ValueError,'Expected 66 columns'):
            parse_publisher_array(b'1,2,3\n','Kinect')


if __name__=='__main__':unittest.main()
