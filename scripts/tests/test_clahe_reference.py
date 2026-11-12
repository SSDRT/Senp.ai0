import copy,json,sys
from pathlib import Path
import numpy as np
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_clahe_reference_candidate import transform_rgb,validate_protocol,SHA,PIPELINE


class ClaheReferenceTests(unittest.TestCase):
    def test_photometry_is_deterministic_and_never_mutates_or_resizes_original(self):
        image=np.random.default_rng(17).integers(30,95,(96,64,3),dtype=np.uint8);before=image.copy()
        a=transform_rgb(image);b=transform_rgb(image)
        np.testing.assert_array_equal(image,before);np.testing.assert_array_equal(a,b)
        self.assertEqual(a.shape,image.shape);self.assertEqual(a.dtype,image.dtype);self.assertTrue(a.flags.c_contiguous)
        self.assertFalse(np.array_equal(a,image))

    def test_invalid_inputs_fail_instead_of_rescaling_or_synthesizing_rgb(self):
        for image in [np.zeros((0,8,3),np.uint8),np.zeros((8,8),np.uint8),np.zeros((8,8,4),np.uint8),np.zeros((8,8,3),float)]:
            with self.assertRaises(ValueError):transform_rgb(image)

    def test_recipe_identity_and_scope_are_fixed_before_reading_sources(self):
        spec=dict(schema='senpai-clahe-reference-protocol/1',model_sha256=SHA,preprocessing_id=PIPELINE,reference_only=True,
            allow_final_test_access=False,allow_validation_access=False,sample_period_ms=66,maximum_duration_ms=None,long_edge_cap_px=960,
            coarse_minimum_confidence=.65,same_subject_torso_fraction=.5,same_subject_floor_normalized=.05,
            clahe_clip_limit=2.0,clahe_tile_grid=[8,8],colour_space='OpenCV_RGB_LAB_L_RGB')
        for key,value in [('clahe_clip_limit',4.0),('clahe_tile_grid',[4,4]),('colour_space','BGR'),('preprocessing_id','mp33-raw-world-workout/1'),('coarse_minimum_confidence',.5),('model_sha256','f'*64),('allow_final_test_access',True),('allow_validation_access',True),('reference_only',False)]:
            candidate=copy.deepcopy(spec);candidate[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_protocol(candidate)


if __name__=='__main__':unittest.main()
