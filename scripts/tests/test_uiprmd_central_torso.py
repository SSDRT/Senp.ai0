import copy,sys,unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from uiprmd_vicon import INDEX
from uiprmd_central_torso import central_torso_features, verify_development_record
from evaluate_uiprmd_central_torso import development_gate


class CentralTorsoTests(unittest.TestCase):
    def fixture(self):
        p=np.full((3,39,3),10.)
        for name,value in {'LSHO':[9,12,10],'RSHO':[11,12,10],'LASI':[9,10,10],'RASI':[11,10,10]}.items():p[:,INDEX[name]]=value
        p[:,INDEX['RELB']]=[[11,11,10],[12,12,10],[11,13,10]]
        p[:,INDEX['LELB']]=[[9,11,10],[8,12,10],[9,13,10]]
        return p

    def test_known_vector_angles_and_input_not_mutated(self):
        p=self.fixture();before=p.copy()
        for v in central_torso_features(p.reshape(3,117)).values():np.testing.assert_allclose(v,[0,90,180])
        np.testing.assert_equal(p,before)

    def test_rigid_rotation_translation_and_uniform_scale_invariance(self):
        p=self.fixture();r=np.array([[0.,-1,0],[1,0,0],[0,0,1]])
        first=central_torso_features(p.reshape(3,117));second=central_torso_features((p@r*2+31).reshape(3,117))
        for name in first:np.testing.assert_allclose(first[name],second[name])

    def test_every_required_marker_stays_missing_without_fallback(self):
        for name in ['LSHO','RSHO','LASI','RASI','RELB']:
            for bad in [[0,0,0],[float('nan'),0,0],[float('inf'),1,1]]:
                p=self.fixture();p[1,INDEX[name]]=bad
                self.assertTrue(np.isnan(central_torso_features(p.reshape(3,117))['angle.right_arm_central_torso'][1]))

    def test_missing_opposite_elbow_does_not_falsely_hide_visible_selected_arm(self):
        p=self.fixture();p[:,INDEX['LELB']]=0
        values=central_torso_features(p.reshape(3,117))
        np.testing.assert_allclose(values['angle.right_arm_central_torso'],[0,90,180]);self.assertTrue(np.isnan(values['angle.left_arm_central_torso']).all())

    def test_collapsed_arm_or_torso_does_not_become_zero_degrees(self):
        for collapsed in ['arm','torso']:
            p=self.fixture()
            if collapsed=='arm':p[:,INDEX['RELB']]=p[:,INDEX['RSHO']]
            else:
                p[:,INDEX['LASI']]=p[:,INDEX['LSHO']];p[:,INDEX['RASI']]=p[:,INDEX['RSHO']]
            self.assertTrue(np.isnan(central_torso_features(p.reshape(3,117))['angle.right_arm_central_torso']).all())

    def test_wrong_shape_and_heldout_records_refused(self):
        with self.assertRaises(ValueError):central_torso_features(np.ones((2,66)))
        record=dict(subject='01',movement='m07',schema='uiprmd-vicon-recording/1',sample_period_ms=10,correct=True,episodes=[dict(correct=True)]*10)
        verify_development_record(record)
        for key,value in [('subject','05'),('subject','08'),('subject','10'),('movement','m02'),('sample_period_ms',33)]:
            bad=copy.deepcopy(record);bad[key]=value
            with self.assertRaises(PermissionError):verify_development_record(bad)

    def test_development_gate_cannot_hide_missing_or_incorrect_reps(self):
        report=dict(counts=dict(total_reps=160,correct_reps=80,incorrect_reps=80),precision=.90,good_rep_recall=.70,assessable_coverage=.80)
        self.assertTrue(development_gate(report))
        for key in ['precision','good_rep_recall','assessable_coverage']:
            bad=copy.deepcopy(report);bad[key]-=.001;self.assertFalse(development_gate(bad))
        for key in report['counts']:
            bad=copy.deepcopy(report);bad['counts'][key]-=1;self.assertFalse(development_gate(bad))


if __name__=='__main__':unittest.main()
