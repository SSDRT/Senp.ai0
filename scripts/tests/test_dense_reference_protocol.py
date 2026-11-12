import copy
import shutil
import subprocess
import tempfile
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_dense_reference_candidate import PIPELINE, validate_protocol
from extract_exercise_quality_features import decoded_frames


class DenseReferenceProtocolTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {'videos': [{'subject': s, 'quality': 'good', 'file': f'{s}-{i}.mp4', 'sha256': str(i)*64}
            for s in ['001', '005'] for i in range(8)]}
        self.protocol = {'schema': 'senpai-dense-reference-research/1', 'sample_period_ms': 33,
            'maximum_duration_ms': None, 'model': 'full', 'long_edge_cap_px': 960, 'preprocessing_id': PIPELINE,
            'reference_only': True, 'allow_final_test_access': False,
            'references': [{'video': r['file'], 'source_sha256': r['sha256']} for r in self.manifest['videos']]}

    def test_exact_fixed_reference_scope_passes_without_reading_trajectories(self):
        self.assertEqual(validate_protocol(self.protocol, self.manifest), self.manifest['videos'])

    def test_source_or_final_test_access_and_parameter_changes_are_blocked(self):
        for key, value in [('reference_only', False), ('allow_final_test_access', True), ('sample_period_ms', 66),
                           ('maximum_duration_ms', 24000), ('model', 'heavy'), ('long_edge_cap_px', 640), ('preprocessing_id', 'same-as-baseline')]:
            changed = dict(self.protocol, **{key: value})
            with self.assertRaises(ValueError):
                validate_protocol(changed, self.manifest)
        changed = dict(self.protocol)
        del changed['maximum_duration_ms']
        with self.assertRaises(ValueError):
            validate_protocol(changed, self.manifest)

    def test_relabelled_added_duplicate_and_changed_reference_receipts_are_blocked(self):
        for mutation in ['label', 'extra', 'duplicate', 'hash']:
            changed = copy.deepcopy(self.manifest)
            if mutation == 'label': changed['videos'][0]['quality'] = 'bad'
            elif mutation == 'extra': changed['videos'].append(dict(changed['videos'][0], file='extra.mp4'))
            elif mutation == 'duplicate': changed['videos'][1] = changed['videos'][0]
            else: changed['videos'][0]['sha256'] = 'changed'
            with self.assertRaises(ValueError):
                validate_protocol(self.protocol, changed)

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'Requires research decoder')
    def test_dense_decode_retains_real_pts_and_identical_pixels_on_shared_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            video = Path(directory)/'source.mp4'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=32x32:rate=30',
                            '-t', '2', '-c:v', 'mpeg4', str(video)], check=True)
            old = list(decoded_frames(video, 66, None))
            dense = list(decoded_frames(video, 33, None))
            self.assertEqual(len(old), 30)
            self.assertEqual(len(dense), 60)
            self.assertEqual([t for t, _, _ in dense], [round(i*1000/30) for i in range(60)])
            lookup = {t: image for t, image, _ in dense}
            for t, image, _ in old:
                self.assertTrue((lookup[t] == image).all())


if __name__ == '__main__':
    unittest.main()
