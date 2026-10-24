import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('uiprmd_fetch',Path(__file__).resolve().parents[1]/'fetch_uiprmd.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class Response(io.BytesIO):
    def __init__(self,body,status=206,content_range='bytes 0-5/6'):
        super().__init__(body);self.status=status;self.headers={'Content-Range':content_range}


class AcquisitionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name)
        self.file={'key':'fixture.zip','links':{'self':'https://example.invalid/fixture'}}
        digest=hashlib.md5(b'abcdef').hexdigest()
        self.data_patch=patch.object(module,'DATA',self.directory);self.data_patch.start();self.addCleanup(self.data_patch.stop)
        self.files_patch=patch.object(module,'FILES',{'fixture.zip':(digest,6)});self.files_patch.start();self.addCleanup(self.files_patch.stop)

    def test_resumed_exact_range_must_reconstruct_the_publisher_checksum(self):
        (self.directory/'fixture.zip.partial').write_bytes(b'ab')
        with patch.object(module.urllib.request,'urlopen',return_value=Response(b'cdef',content_range='bytes 2-5/6')) as opened:
            result=module.fetch(self.file)
        self.assertEqual('bytes=2-5',opened.call_args.args[0].get_header('Range'))
        self.assertEqual(b'abcdef',(self.directory/'fixture.zip').read_bytes())
        self.assertEqual(hashlib.sha256(b'abcdef').hexdigest(),result['sha256'])

    def test_server_ignoring_range_cannot_make_a_ready_archive(self):
        with patch.object(module.urllib.request,'urlopen',return_value=Response(b'abcdef',status=200)):
            with self.assertRaisesRegex(ValueError,'exact bounded range'):module.fetch(self.file)
        self.assertFalse((self.directory/'fixture.zip').exists())

    def test_short_or_corrupted_body_is_retained_without_promotion(self):
        for body in [b'abc',b'abcdeg']:
            with patch.object(module.time,'sleep'),patch.object(module.urllib.request,'urlopen',side_effect=lambda *a,**k:Response(body)):
                with self.assertRaises(ValueError):module.fetch(self.file)
            self.assertFalse((self.directory/'fixture.zip').exists())

    def test_existing_bad_archive_is_preserved_and_not_silently_replaced(self):
        path=self.directory/'fixture.zip';path.write_bytes(b'wrong!')
        with self.assertRaisesRegex(ValueError,'Existing archive fails'):module.fetch(self.file)
        self.assertEqual(b'wrong!',path.read_bytes())

    def test_a_transient_truncated_range_is_retried_without_appending_bad_bytes(self):
        with patch.object(module.time,'sleep'),patch.object(module.urllib.request,'urlopen',side_effect=[Response(b'abc'),Response(b'abcdef')]) as opened:
            module.fetch(self.file)
        self.assertEqual(2,opened.call_count)
        self.assertEqual(b'abcdef',(self.directory/'fixture.zip').read_bytes())


if __name__=='__main__':unittest.main()
