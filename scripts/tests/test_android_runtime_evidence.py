import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('runtime', Path(__file__).resolve().parents[1] / 'verify_android_runtime.py')
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class AndroidRuntimeEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.xml = self.root / 'TEST-native.xml'
        self.xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                            '<testcase classname="Native" name="camera"/></testsuite>')
        (self.root / 'app.apk').write_bytes(b'frozen apk fixture')
        self.protocol = dict(schema='senpai-second-runtime-protocol/1', target_api=35, existing_api=36,
                             abi='x86_64', verified_engine_commit='a' * 40,
                             modules={'native': {'test_ids': [['Native', 'camera']]}},
                             apk_receipts=[dict(path='app.apk', sha256=runtime.sha256(self.root / 'app.apk'))])
        self.boot = dict(serial='emulator-5580', page_bytes='4096', properties={
            'ro.build.version.sdk': '35', 'ro.kernel.qemu': '1', 'sys.boot_completed': '1',
            'ro.product.cpu.abi': 'x86_64'})

    def verify(self):
        return runtime.verify_runtime(self.protocol, self.boot, {'native': [self.xml]}, self.root)

    def test_complete_matching_runtime_has_no_accuracy_or_performance_claim(self):
        result = self.verify()
        self.assertEqual(1, result['native_total'])
        self.assertIsNone(result['actual_live_rep_precision'])
        self.assertFalse(result['physical'])
        self.assertFalse(result['sustained_performance_measured'])

    def test_api_alias_or_unbooted_device_cannot_earn_a_second_runtime(self):
        for key, value in [('ro.build.version.sdk', '36'), ('ro.kernel.qemu', '0'),
                           ('sys.boot_completed', '0'), ('ro.product.cpu.abi', 'arm64-v8a')]:
            before = copy.deepcopy(self.boot)
            self.boot['properties'][key] = value
            with self.assertRaises(ValueError):
                self.verify()
            self.boot = before
        self.protocol['existing_api'] = 35
        with self.assertRaises(ValueError):
            self.verify()

    def test_declared_counts_cannot_replace_actual_cases(self):
        self.xml.write_text('<testsuite tests="29" failures="0" errors="0" skipped="0"/>')
        with self.assertRaises(ValueError):
            self.verify()

    def test_skipped_or_failed_cases_cannot_hide_behind_green_totals(self):
        for child in ('failure', 'error', 'skipped'):
            self.xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                                f'<testcase classname="Native" name="camera"><{child}/></testcase></testsuite>')
            with self.assertRaises(ValueError):
                self.verify()

    def test_duplicated_or_substituted_cases_fail(self):
        self.xml.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0">'
                            '<testcase classname="Native" name="camera"/>'
                            '<testcase classname="Native" name="camera"/></testsuite>')
        with self.assertRaises(ValueError):
            self.verify()
        self.xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                            '<testcase classname="Native" name="other"/></testsuite>')
        with self.assertRaises(ValueError):
            self.verify()

    def test_missing_module_or_changed_apk_fails(self):
        with self.assertRaises(ValueError):
            runtime.verify_runtime(self.protocol, self.boot, {}, self.root)
        (self.root / 'app.apk').write_bytes(b'changed apk')
        with self.assertRaises(ValueError):
            self.verify()


if __name__ == '__main__':
    unittest.main()
