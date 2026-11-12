import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts'))
from summarize_autonomous_pose_experiments import select_reference_models

class AutonomousReferenceSelectionTests(unittest.TestCase):
    def rows(self):
        base={'reference_video_sha256':'a'*64,'exercise':'biceps_curl','side':'LEFT','view':'side'}
        return base|{'status':'REPLAYED','counts':{'qualified':0},'weak_whole_clip_label':'bad'},base|{'status':'REPLAYED','counts':{'qualified':100},'weak_whole_clip_label':'good'}
    def test_source_scores_and_labels_cannot_select_a_backend(self):
        f,h=self.rows();before=select_reference_models([f],[h])
        f['counts']['qualified']=1000;f['weak_whole_clip_label']='good';h['counts']['qualified']=0;h['weak_whole_clip_label']='bad'
        self.assertEqual(before,select_reference_models([f],[h]));self.assertEqual(['full'],list(before.values()))
    def test_fallback_requires_usable_matching_reference_and_stays_fixed(self):
        f,h=self.rows();f['status']='MISSING_FEATURE'
        self.assertEqual(['heavy'],list(select_reference_models([f],[h]).values()))
        h['status']='MISSING_FEATURE';self.assertEqual(['unavailable'],list(select_reference_models([f],[h]).values()))
        different=copy.deepcopy(h);different['status']='REPLAYED'
        with self.assertRaises(ValueError):select_reference_models([f],[h,different])
        h['side']='RIGHT'
        with self.assertRaises(ValueError):select_reference_models([f],[h])

if __name__=='__main__':unittest.main()
