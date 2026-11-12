import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_image_reference_candidates import PIPELINES, crop_bounds, map_to_original, same_current_subject, usable, validate_protocol


def points():
    rows = [[.5,.5,.1,.95,.9,1.,2.,3.] for _ in range(33)]
    for i in [11,12]:rows[i][1]=.25
    for i in [23,24]:rows[i][1]=.6
    for i in [15,16]:rows[i][0]=.2 if i==15 else .8
    for i in [27,28]:rows[i][1]=.95
    rows[0][1]=.1
    return rows


class ImageReferenceGeometryTests(unittest.TestCase):
    def test_crop_uses_current_observed_body_and_clips_only_pixel_bounds(self):
        raw=points();before=copy.deepcopy(raw)
        crop=crop_bounds(raw,400,600)
        self.assertIsNotNone(crop)
        self.assertEqual(raw,before)
        self.assertTrue(0<=crop[0]<crop[2]<=400 and 0<=crop[1]<crop[3]<=600)
        self.assertEqual(crop[3],600)

    def test_missing_low_confidence_nonfinite_or_off_image_anchor_cannot_define_crop(self):
        for change in ['missing','confidence','nonfinite','outside']:
            raw=points()
            if change=='missing':raw=raw[:32]
            elif change=='confidence':raw[27][3]=.2
            elif change=='nonfinite':raw[23][0]=float('nan')
            else:raw[15][0]=1.2
            self.assertIsNone(crop_bounds(raw,400,600))

    def test_mapping_is_invertible_and_never_modifies_confidence_or_world_values(self):
        raw=points();before=copy.deepcopy(raw)
        mapped=map_to_original(raw,(100,50,300,350),400,600)
        for p,q in zip(raw,mapped):
            self.assertAlmostEqual((q[0]*400-100)/200,p[0])
            self.assertAlmostEqual((q[1]*600-50)/300,p[1])
            self.assertAlmostEqual(q[2]*400/200,p[2])
            self.assertEqual(q[3:],p[3:])
        self.assertEqual(raw,before)
        self.assertEqual(map_to_original(raw,(0,0,400,600),400,600),raw)
        self.assertEqual(map_to_original([], (0,0,400,600),400,600),[])
        with self.assertRaises(ValueError):map_to_original(raw[:32],(0,0,400,600),400,600)
        with self.assertRaises(ValueError):map_to_original(raw,(0,0,0,600),400,600)

    def test_subject_binding_uses_current_image_anchors_without_confidence_or_world_repair(self):
        coarse=points();refined=copy.deepcopy(coarse)
        self.assertTrue(same_current_subject(coarse,refined))
        for p in refined:p[5:8]=[50.,-20.,-100.]
        self.assertTrue(same_current_subject(coarse,refined))
        for p in refined:p[0]+=.4
        self.assertFalse(same_current_subject(coarse,refined))
        refined=points();refined[11][4]=.2
        self.assertFalse(same_current_subject(coarse,refined))
        self.assertFalse(usable([], (11,12,23,24)))

    def test_reference_scope_and_parameters_cannot_be_replaced_by_source_or_final_data(self):
        manifest={'videos':[{'subject':s,'quality':'good','file':f'{s}-{i}.mp4','sha256':str(i)*64} for s in ['001','005'] for i in range(8)]}
        protocol={'schema':'senpai-image-reference-research/1','reference_only':True,'allow_final_test_access':False,
            'sample_period_ms':66,'maximum_duration_ms':None,'pipelines':PIPELINES,'long_edge_cap_px':960,
            'crop_padding_fraction':.2,'coarse_minimum_confidence':.65,'minimum_crop_edge_px':64,
            'subject_agreement_torso_fraction':.5,'subject_agreement_floor_normalized':.05,
            'references':[{'video':r['file'],'source_sha256':r['sha256']} for r in manifest['videos']]}
        self.assertEqual(len(validate_protocol(protocol,manifest)),16)
        for key,value in [('reference_only',False),('allow_final_test_access',True),('sample_period_ms',33),
                          ('crop_padding_fraction',.5),('coarse_minimum_confidence',.2),('maximum_duration_ms',24000)]:
            with self.assertRaises(ValueError):validate_protocol(dict(protocol,**{key:value}),manifest)
        changed=copy.deepcopy(protocol);changed['references'][0]['source_sha256']='different'
        with self.assertRaises(ValueError):validate_protocol(changed,manifest)


if __name__=='__main__':unittest.main()
