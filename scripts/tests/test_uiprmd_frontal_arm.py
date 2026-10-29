import sys,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_frontal_arm import FEATURES,frontal_arm_features,verify_development_record
from uiprmd_vicon import INDEX


class FrontalArmTests(unittest.TestCase):
    def sample(self,elevation=90,off_plane=0):
        p=np.zeros((1,39,3))
        for name,value in {'LSHO':[-1,0,3],'RSHO':[1,0,3],'LASI':[-1,0,1],'RASI':[1,0,1]}.items():p[0,INDEX[name]]=value
        e,o=np.deg2rad([elevation,off_plane]);arm=np.array([np.sin(e)*np.cos(o),np.sin(o),-np.cos(e)*np.cos(o)])
        p[0,INDEX['RELB']]=p[0,INDEX['RSHO']]+arm
        p[0,INDEX['LELB']]=p[0,INDEX['LSHO']]+arm*np.array([-1,1,1])
        return p.reshape(1,117)
    def test_known_in_plane_and_out_of_plane_components_are_distinct(self):
        for e in [0,45,90,135,180]:
            v=frontal_arm_features(self.sample(e))
            for side in ['left','right']:
                self.assertAlmostEqual(e,v[FEATURES[side]][0]);self.assertAlmostEqual(0,v['angle.'+side+'_arm_out_of_frontal_plane'][0]);self.assertAlmostEqual(1,v['relation.'+side+'_arm_projected_fraction'][0])
        v=frontal_arm_features(self.sample(135,60));self.assertAlmostEqual(135,v[FEATURES['right']][0]);self.assertAlmostEqual(60,v['angle.right_arm_out_of_frontal_plane'][0]);self.assertAlmostEqual(.5,v['relation.right_arm_projected_fraction'][0])
    def test_ill_conditioned_projection_missing_but_measured_normal_component_retained(self):
        for off in [80,90]:
            v=frontal_arm_features(self.sample(90,off));self.assertTrue(np.isnan(v[FEATURES['right']][0]));self.assertAlmostEqual(off,v['angle.right_arm_out_of_frontal_plane'][0])
    def test_each_missing_anchor_and_degenerate_body_axes_refuse_fallback(self):
        for name in ['LSHO','RSHO','LASI','RASI','RELB']:
            for value in [0,np.nan,np.inf]:
                a=self.sample();a.reshape(1,39,3)[0,INDEX[name]]=value
                self.assertTrue(np.isnan(frontal_arm_features(a)[FEATURES['right']][0]))
        a=self.sample();p=a.reshape(1,39,3);p[0,INDEX['LASI']]=[1,0,3];p[0,INDEX['RASI']]=[3,0,3]
        self.assertTrue(np.isnan(frontal_arm_features(a)[FEATURES['right']][0]))
    def test_proper_rotation_translation_scale_and_input_immutability(self):
        a=self.sample(125,35);before=a.copy();v=frontal_arm_features(a)
        angle=.71;rot=np.array([[np.cos(angle),0,np.sin(angle)],[0,1,0],[-np.sin(angle),0,np.cos(angle)]])
        points=a.reshape(1,39,3).copy();mask=np.linalg.norm(points,axis=2)>0;points[mask]=points[mask]@rot.T*13+[50,80,120]
        transformed=frontal_arm_features(points.reshape(1,117))
        for key in v:np.testing.assert_allclose(v[key],transformed[key],atol=1e-10,equal_nan=True)
        np.testing.assert_array_equal(a,before)
    def test_pointwise_prefix_never_uses_later_samples(self):
        a=np.concatenate([self.sample(30),self.sample(90,80),self.sample(150)],axis=0);whole=frontal_arm_features(a);prefix=frontal_arm_features(a[:2])
        for key in whole:np.testing.assert_allclose(whole[key][:2],prefix[key],equal_nan=True)
    def test_frozen_intake_rejects_heldout_or_changed_measurement_kind(self):
        row=dict(subject='01',movement='m07',schema='uiprmd-vicon-recording/1',sample_period_ms=10,correct=True,episodes=[dict(correct=True)]*10)
        verify_development_record(row)
        for update in [dict(subject='05'),dict(subject='08'),dict(schema='mp33')]:
            with self.assertRaises(PermissionError):verify_development_record(dict(row,**update))


if __name__=='__main__':unittest.main()
