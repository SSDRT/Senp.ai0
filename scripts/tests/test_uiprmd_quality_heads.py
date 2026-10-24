import sys
from pathlib import Path
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_vicon import INDEX
from uiprmd_quality_features import frame_features,aggregate
from train_uiprmd_quality_heads import split_indices,evaluate_veto,model


def geometry():
    p=np.tile([3.,4.,6.],(1,39,1))
    for name,point in {'LASI':[2,4,5],'RASI':[4,4,5],'LSHO':[2,4,7],'RSHO':[4,4,7],
        'LELB':[1,4,7],'RELB':[4,3,7],'LKNE':[2,4,4],'RKNE':[4,4,4],
        'LANK':[2,4,3],'RANK':[4,4,3],'LWRA':[1,4,6],'LWRB':[1,4,6.2],
        'RWRA':[4,3,6],'RWRB':[4,3,6.2]}.items():p[:,INDEX[name]]=point
    return p


class QualityHeadTests(unittest.TestCase):
    def test_relative_geometry_is_invariant_to_rigid_transform_and_scale(self):
        p=geometry();a=frame_features(p.reshape(1,117))
        r=np.array([[0.,-1,0],[1,0,0],[0,0,1]])
        b=frame_features((p@r*1.8+10).reshape(1,117))
        self.assertEqual(16,len(a))
        for name in a:np.testing.assert_allclose(a[name],b[name],atol=1e-6,equal_nan=True)

    def test_known_arm_planes_body_line_and_width_ratio(self):
        a=frame_features(geometry().reshape(1,117))
        self.assertAlmostEqual(90,a['angle.right_arm_out_of_torso_plane'][0])
        self.assertAlmostEqual(0,a['angle.left_arm_out_of_torso_plane'][0])
        self.assertAlmostEqual(180,a['angle.left_body_line'][0])
        self.assertAlmostEqual(1,a['relation.knee_width_over_feet'][0])

    def test_missing_wrist_or_degenerate_anchor_stays_missing(self):
        p=geometry();p[:,INDEX['LWRA']]=0
        a=frame_features(p.reshape(1,117))
        self.assertTrue(np.isnan(a['relation.hand_width_over_shoulders'][0]))
        p=geometry();p[:,INDEX['RANK']]=p[:,INDEX['LANK']]
        self.assertTrue(np.isnan(frame_features(p.reshape(1,117))['relation.knee_width_over_feet'][0]))

    def test_aggregate_cannot_read_future_frames_and_reports_missing_fraction(self):
        a={'x':np.array([1.,2.,3.,999.]),'missing':np.array([np.nan,np.nan,np.nan,1.])}
        vector,names=aggregate(a,0,2)
        b={k:v[:3] for k,v in a.items()}
        np.testing.assert_allclose(vector,aggregate(b,0,2)[0],equal_nan=True)
        self.assertEqual(0,vector[names.index('missing.observed_fraction')])
        with self.assertRaises(ValueError):aggregate(a,0,4)

    def test_subject_split_refuses_validation_and_test_members(self):
        a,b=split_indices(['01','02','03','04'],'01')
        self.assertEqual([1,2,3],a.tolist());self.assertEqual([0],b.tolist())
        for held in ['05','08']:
            with self.assertRaises(PermissionError):split_indices(['01',held],'01')

    def test_imputation_fits_only_training_samples(self):
        classifier=model('logistic');x=np.array([[1.,np.nan],[3.,2.],[2.,4.],[4.,3.]])
        classifier.fit(x,[0,1,0,1]);before=classifier.steps[0][1].statistics_.copy()
        classifier.predict_proba([[9999.,9999.]])
        np.testing.assert_array_equal(before,classifier.steps[0][1].statistics_)

    def test_veto_only_removes_existing_qualified_counts_and_never_mutates_replay(self):
        row={'subject':'01','movement':'m01','episodes':[{'episode':1,'start_ms':0,'end_ms':990,'correct':True},
            {'episode':2,'start_ms':1000,'end_ms':1990,'correct':False}],
            'attempts':[{'started_ms':100,'completed_ms':900,'verdict':'ACCEPTABLE'},
                        {'started_ms':1100,'completed_ms':1900,'verdict':'UNASSESSABLE'}]}
        replay={'rows':[row]};samples=[{'row':0,'attempt':0},{'row':0,'attempt':1}]
        result=evaluate_veto(replay,samples,[1.,0.])
        self.assertEqual(0,result['counts'].get('true_positive',0))
        self.assertEqual(1,result['counts']['verdict_UNASSESSABLE'])
        self.assertEqual('ACCEPTABLE',row['attempts'][0]['verdict'])
        for scores in [[0.],[-1.,0.],[float('nan'),0.]]:
            with self.assertRaises(ValueError):evaluate_veto(replay,samples,scores)
        with self.assertRaises(ValueError):evaluate_veto(replay,[samples[0],samples[0]],[0.,0.])


if __name__=='__main__':unittest.main()
