from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fetch_lite_reference_model import crc32c,verify_model


class LiteAcquisitionTests(unittest.TestCase):
    def test_crc32c_castagnoli_known_vectors(self):
        self.assertEqual(0,crc32c(b''))
        self.assertEqual(0xE3069283,crc32c(b'123456789'))

    def test_wrong_or_truncated_content_never_passes_publisher_verification(self):
        for content in (b'',b'PK\x03\x04wrong',b'123456789'):
            with self.assertRaises(ValueError):verify_model(content,{})


if __name__=='__main__':unittest.main()
