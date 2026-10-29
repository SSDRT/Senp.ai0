import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verify_native_transcript import inventory


class NativeTranscriptTests(unittest.TestCase):
    def text(self):
        return ('INSTRUMENTATION_STATUS: class=a.C\nINSTRUMENTATION_STATUS: test=case\n'
                'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=a.C\n'
                'INSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS_CODE: 0\n'
                'OK (1 test)\nINSTRUMENTATION_CODE: -1\n')

    def test_exact_started_and_passed_identity(self):
        self.assertEqual([('a.C','case')],inventory(self.text(),[('a.C','case')]))

    def test_failure_skip_or_partial_summary_cannot_pass(self):
        for text in [self.text().replace('CODE: 0','CODE: -2'),
                     self.text().replace('CODE: 0','CODE: -3'),
                     self.text().replace('OK (1 test)',''),
                     self.text().replace('CODE: -1','CODE: 0'),
                     self.text()+'INSTRUMENTATION_FAILED: process died\n']:
            with self.assertRaises(ValueError): inventory(text,[('a.C','case')])

    def test_duplicate_unpaired_and_different_inventory_refused(self):
        start=self.text().split('INSTRUMENTATION_STATUS_CODE: 1\n')[0]+'INSTRUMENTATION_STATUS_CODE: 1\n'
        for text in [start+self.text(),self.text().replace(start,''),self.text()+start]:
            with self.assertRaises(ValueError): inventory(text,[('a.C','case')])
        with self.assertRaises(ValueError): inventory(self.text(),[('a.C','different')])

    def test_conflicting_identity_and_duplicate_frozen_case_refused(self):
        with self.assertRaises(ValueError): inventory(self.text().replace('class=a.C','class=a.C\nINSTRUMENTATION_STATUS: class=b.C',1),[('a.C','case')])
        with self.assertRaises(ValueError): inventory(self.text(),[('a.C','case')]*2)


if __name__=='__main__': unittest.main()
