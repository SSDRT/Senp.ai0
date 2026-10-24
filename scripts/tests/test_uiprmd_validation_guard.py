import sys
from pathlib import Path
import unittest
from unittest.mock import Mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from prepare_uiprmd_validation import read_validation_member


class ValidationGuardTests(unittest.TestCase):
    def test_final_test_and_unapproved_measurements_are_rejected_before_read(self):
        archive = Mock()
        for member in ['Vicon/Positions/m01_s08_positions.txt','Vicon/Positions/m07_s10_e01_positions_inc.txt',
                       'Vicon/Positions/m01_s01_positions.txt','Kinect/Positions/m01_s05_positions.txt',
                       'Vicon/Positions/m02_s05_positions.txt','Vicon/Angles/m01_s05_angles.txt',
                       '../Vicon/Positions/m01_s05_positions.txt','/Vicon/Positions/m01_s05_positions.txt']:
            with self.assertRaises(PermissionError):read_validation_member(archive,member)
        archive.read.assert_not_called()

    def test_original_validation_bytes_are_returned_unchanged(self):
        archive = Mock();archive.read.return_value = b'original validation bytes'
        self.assertEqual(b'original validation bytes',read_validation_member(archive,'Movements/Vicon/Positions/m01_s05_positions.txt'))
        archive.read.assert_called_once()


if __name__ == '__main__':unittest.main()
