from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_lite_reference_candidate import SHA,PIPELINE,validate_protocol


class LiteScopeTests(unittest.TestCase):
    def test_changed_model_pipeline_or_gate_is_rejected_before_data_access(self):
        spec=dict(schema='senpai-lite-reference-protocol/1',model_sha256=SHA,preprocessing_id=PIPELINE,
            reference_only=True,allow_final_test_access=False,allow_validation_access=False,
            sample_period_ms=66,maximum_duration_ms=None,long_edge_cap_px=960,coarse_minimum_confidence=.65,
            same_subject_torso_fraction=.5,same_subject_floor_normalized=.05)
        for key,value in [('model_sha256','f'*64),('preprocessing_id','mp33-raw-world-workout/1'),
            ('allow_final_test_access',True),('allow_validation_access',True),('reference_only',False),
            ('coarse_minimum_confidence',.5),('maximum_duration_ms',24000),('sample_period_ms',33)]:
            candidate=dict(spec);candidate[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_protocol(candidate)


if __name__=='__main__':unittest.main()
