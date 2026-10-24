import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('readiness',ROOT/'scripts/score_readiness.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class ReadinessScoringTests(unittest.TestCase):
    def setUp(self):
        self.rubric=json.loads((ROOT/'models/readiness-rubric.json').read_text())
        self.base={'verified_engine_commit':'a'*40,'reference_total':32,'reference_usable':12,
            'camera_exercise_ids':['bodyweight_squat','biceps_curl','push_up','overhead_press'],
            'mechanism_and_scoped_tests_pass':True,'android_runtimes':[{'api':36,'emulator':True,'all_tests_pass':True}]}

    def labels(self):
        return {'subjects':{'development':['01','02','03','04'],'validation':['05','06','07'],'test':['08','09','10']},
            'measurement_domain':'skeletal',
            'existing_independent_labels':True,'frozen_before_test':True,'exercise_ids':['bodyweight_squat','lateral_raise'],
            'correct_test_reps':60,'incorrect_test_reps':60,'precision':.93,'good_rep_recall':.8,'assessable_coverage':.9}

    def test_formal_baseline_is_not_seventy_and_has_no_accuracy_claim(self):
        result=module.score(self.base,self.rubric)
        self.assertEqual(42.5,result['reported_score']);self.assertFalse(result['milestone_reached'])
        self.assertIsNone(result['actual_live_rep_precision'])

    def test_more_cases_or_duplicate_runtime_entries_cannot_raise_the_score(self):
        self.base['extra_generated_tests']=100000
        self.base['android_runtimes']*=10
        self.assertEqual(42.5,module.score(self.base,self.rubric)['reported_score'])

    def test_seen_people_or_unfrozen_test_data_cannot_earn_independent_points(self):
        for field in ['overlap','unfrozen','too_few','low_precision']:
            labels=self.labels()
            if field=='overlap':labels['subjects']['development'].append('08')
            if field=='unfrozen':labels['frozen_before_test']=False
            if field=='too_few':labels['correct_test_reps']=29
            if field=='low_precision':labels['precision']=.89
            self.base['independent_skeleton']=labels
            self.assertEqual(0,module.score(self.base,self.rubric)['points']['independent_label_validation'])

    def test_skeleton_labels_never_earn_camera_points(self):
        self.base['independent_skeleton']=self.labels()
        self.base['independent_camera']=self.labels()
        result=module.score(self.base,self.rubric)
        self.assertEqual(10,result['points']['independent_label_validation'])
        self.assertIsNone(result['actual_live_rep_precision'])

    def test_high_raw_score_without_independent_labels_is_capped_below_target(self):
        self.base.update(reference_usable=32,camera_exercise_ids=self.rubric['policy_ids'],android_runtimes=[
            {'api':36,'emulator':True,'all_tests_pass':True},{'api':35,'emulator':True,'all_tests_pass':True},
            {'api':34,'emulator':False,'all_tests_pass':True}])
        self.assertEqual(69,module.score(self.base,self.rubric)['reported_score'])
        self.assertFalse(module.score(self.base,self.rubric)['milestone_reached'])

    def test_denominator_and_weight_changes_fail_closed(self):
        self.base['reference_total']=12
        with self.assertRaises(ValueError):module.score(self.base,self.rubric)
        self.base['reference_total']=32
        changed=copy.deepcopy(self.rubric);changed['weights']['independent_label_validation']=0
        with self.assertRaises(ValueError):module.score(self.base,changed)

    def test_nonfinite_independent_metrics_and_missing_soak_cannot_earn_points(self):
        self.base['independent_skeleton']=self.labels()
        self.base['independent_skeleton']['precision']=float('inf')
        self.base['live_soak']=None
        result=module.score(self.base,self.rubric)
        self.assertEqual(0,result['points']['independent_label_validation'])
        self.assertEqual(0,result['points']['sustained_live_performance'])

    def test_target_and_policy_denominators_are_frozen(self):
        changed=copy.deepcopy(self.rubric);changed['target']=40
        with self.assertRaises(ValueError):module.score(self.base,changed)
        changed=copy.deepcopy(self.rubric);changed['policy_ids'].append('another')
        with self.assertRaises(ValueError):module.score(self.base,changed)


if __name__=='__main__':unittest.main()
