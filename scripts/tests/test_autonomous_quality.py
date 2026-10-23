import copy
from pathlib import Path
import sys
import unittest
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_exercise_quality_features import FEATURES
from train_autonomous_quality_candidates import aggregate_clip,candidates

class AutonomousQualityTests(unittest.TestCase):
    def test_missing_observations_do_not_become_landmarks_or_velocity_across_gaps(self):
        frames=[]
        for time,value in [(0,10),(500,20),(1000,30),(5000,130)]:
            values=[None]*len(FEATURES);values[0]=value
            frames.append({'timestamp_ms':time,'features':values})
        video={'frames':frames};original=copy.deepcopy(video)
        values,usable=aggregate_clip(video)
        self.assertEqual(1,usable);self.assertEqual(20.0,values[5])
        self.assertTrue(all(np.isnan(values[i]) for i in [6,7,8,9,11]))
        self.assertEqual(0.0,values[10]);self.assertEqual(original,video)

    def test_imputation_is_fit_on_training_values_and_never_refit_by_predict(self):
        estimator=candidates()['logistic_C1']
        x=np.asarray([[0,1],[1,2],[2,np.nan],[3,3],[4,4],[5,5]],dtype=float)
        estimator.fit(x,[0,0,0,1,1,1])
        before=estimator.steps[0][1].statistics_.copy()
        estimator.predict_proba([[1000,np.nan],[np.nan,1000]])
        np.testing.assert_array_equal(before,estimator.steps[0][1].statistics_)

if __name__=='__main__':unittest.main()
