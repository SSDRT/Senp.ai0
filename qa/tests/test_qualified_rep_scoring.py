import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts'))
from score_qualified_reps import score

class QualifiedRepScoringTests(unittest.TestCase):
    def sample(self):
        identity={'video_sha256':'a'*64,'reference_video_sha256':'b'*64,'exercise':'biceps_curl','side':'LEFT','evaluated_start_ms':0,'evaluated_end_ms':1000}
        report={'rows':[identity|{'status':'REPLAYED','attempts':[{'started_ms':100,'completed_ms':900,'verdict':'CORRECT'}]}]}
        annotations={'schema':'rep-acceptance-annotations/1','complete_review':True,'tasks':[identity|{'reviewed_entire_clip':True,'expert_reviewed':True,
            'reviewers':['unit-reviewer-a','unit-reviewer-b'],'split':'development','previously_seen':True,'attempts':[{'start_ms':100,'end_ms':900,'label':'CORRECT'}]}]}
        return report,annotations
    def test_reviewed_good_rep_scores_correctly(self):
        result=score(*self.sample());self.assertEqual(1,result['qualified_precision']);self.assertEqual(1,result['good_acceptance'])
        self.assertFalse(result['release_qualified'])
    def test_duplicate_accepted_event_is_a_false_positive(self):
        report,annotations=self.sample();report['rows'][0]['attempts']*=2
        result=score(report,annotations);self.assertEqual(.5,result['qualified_precision']);self.assertEqual(1,result['counts']['unmatched_accepted'])
    def test_unavailable_prediction_stays_in_recall_and_coverage_denominators(self):
        report,annotations=self.sample();report['rows'][0]['status']='MISSING_FEATURE'
        result=score(report,annotations);self.assertEqual(0,result['good_acceptance']);self.assertEqual(0,result['decision_coverage'])
    def test_bad_and_unassessable_acceptances_are_not_correct_predictions(self):
        for label in ['INCORRECT','UNASSESSABLE']:
            report,annotations=self.sample();annotations['tasks'][0]['attempts'][0]['label']=label
            result=score(report,annotations);self.assertEqual(0,result['qualified_precision'])
            self.assertEqual(1,result['bad_acceptance'] if label=='INCORRECT' else result['unknown_acceptance'])
    def test_templates_weak_labels_and_insufficient_review_are_refused(self):
        for field,value in [('complete_review',False),('reviewed_entire_clip',False),('expert_reviewed',False),('reviewers',['same','same'])]:
            report,annotations=self.sample()
            if field=='complete_review':annotations[field]=value
            else:annotations['tasks'][0][field]=value
            with self.assertRaises(ValueError):score(report,annotations)
    def test_seen_people_cannot_be_renamed_a_fresh_test_and_hashes_must_match(self):
        report,annotations=self.sample();annotations['tasks'][0]['split']='test'
        with self.assertRaises(ValueError):score(report,annotations)
        report,annotations=self.sample();annotations['tasks'][0]['video_sha256']='c'*64
        with self.assertRaises(ValueError):score(report,annotations)
        for end in [None,True,900]:
            report,annotations=self.sample();annotations['tasks'][0]['evaluated_end_ms']=end
            with self.assertRaises(ValueError):score(report,annotations)

if __name__=='__main__':unittest.main()
