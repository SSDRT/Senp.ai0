import sys,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_pelvis_knee import FEATURES,pelvis_knee_features,verify_frontal_export
from uiprmd_vicon import INDEX,measurement_features


class PelvisKneeTests(unittest.TestCase):
    def sample(self,angle=90,lateral=0):
        p=np.zeros((1,39,3));a=np.deg2rad(angle)
        for prefix,sign in [('L',-1),('R',1)]:
            hip=np.array([sign*100,100,1000]);knee=np.array([sign*100+lateral,100,500]);ankle=knee+np.array([lateral,500*np.sin(a),500*np.cos(a)])
            for name,v in [('ASI',hip),('KNE',knee),('ANK',ankle)]:p[0,INDEX[prefix+name]]=v
        return p.reshape(1,117)
    def test_in_plane_known_angles_and_measured_components(self):
        for angle in [0,45,90,135,180]:
            v=pelvis_knee_features(self.sample(angle))
            for side in ['left','right']:
                self.assertAlmostEqual(angle,v[FEATURES[side]][0]);self.assertAlmostEqual(0,v['angle.'+side+'_thigh_out_of_pelvis_plane'][0]);self.assertAlmostEqual(1,v['relation.'+side+'_shin_projected_fraction'][0])
    def test_lateral_component_is_retained_and_not_called_a_corrected_joint(self):
        a=self.sample(90,300);v=pelvis_knee_features(a)
        self.assertAlmostEqual(90,v[FEATURES['left']][0]);self.assertGreater(v['angle.left_thigh_out_of_pelvis_plane'][0],0)
        self.assertGreater(abs(measurement_features(a)['angle.left_knee'][0]-90),1)
    def test_conditioning_missing_and_degenerate_rays_never_fallback(self):
        for lateral in [3000,100000]:self.assertTrue(np.isnan(pelvis_knee_features(self.sample(90,lateral))[FEATURES['left']][0]))
        for name in ['LASI','RASI','LKNE','LANK']:
            for bad in [0,np.nan,np.inf]:
                a=self.sample();a.reshape(1,39,3)[0,INDEX[name]]=bad
                self.assertTrue(np.isnan(pelvis_knee_features(a)[FEATURES['left']][0]))
        a=self.sample();p=a.reshape(1,39,3);p[0,INDEX['RASI']]=p[0,INDEX['LASI']]
        self.assertTrue(np.isnan(pelvis_knee_features(a)[FEATURES['left']][0]))
    def test_proper_rigid_transform_scale_and_input_immutability(self):
        a=self.sample(110,200);before=a.copy();v=pelvis_knee_features(a);theta=.61;rot=np.array([[np.cos(theta),0,np.sin(theta)],[0,1,0],[-np.sin(theta),0,np.cos(theta)]])
        p=a.reshape(1,39,3).copy();present=np.linalg.norm(p,axis=2)>0;p[present]=p[present]@rot.T*3+[500,700,900];new=pelvis_knee_features(p.reshape(1,117))
        for key in v:np.testing.assert_allclose(v[key],new[key],atol=1e-10,equal_nan=True)
        np.testing.assert_array_equal(a,before)
    def test_pointwise_prefix_is_causal(self):
        a=np.concatenate([self.sample(90),self.sample(110,3000),self.sample(160)]);v=pelvis_knee_features(a);prefix=pelvis_knee_features(a[:2])
        for key in v:np.testing.assert_allclose(v[key][:2],prefix[key],equal_nan=True)
    def test_intake_rejects_heldout_and_wrong_measurement(self):
        r=dict(subject='01',movement='m01',schema='uiprmd-frontal-arm-recording/1',sample_period_ms=10,correct=True,episodes=[dict(correct=True)]*10);verify_frontal_export(r)
        for update in [dict(subject='05'),dict(subject='10'),dict(schema='mp33')]:
            with self.assertRaises(PermissionError):verify_frontal_export(dict(r,**update))


if __name__=='__main__':unittest.main()
