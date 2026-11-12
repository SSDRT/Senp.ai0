import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from evaluate_uiprmd_development import evaluate_rows, match_events


def episode(start=0,correct=True):
    return {'episode':1,'start_ms':start,'end_ms':start+990,'correct':correct}


def attempt(start=100,verdict='ACCEPTABLE'):
    return {'started_ms':start,'completed_ms':start+800,'verdict':verdict}


def row(episodes,attempts):
    return {'subject':'01','movement':'m01','episodes':episodes,'attempts':attempts}


class UiprmdMetricTests(unittest.TestCase):
    def test_qualified_bad_and_duplicate_good_events_are_both_false_positives(self):
        result=evaluate_rows([row([episode(),episode(1000,False)],
                                 [attempt(),attempt(1100),attempt(120)])])
        self.assertEqual(1,result['counts']['true_positive'])
        self.assertEqual(2,result['counts']['false_positive'])
        self.assertEqual(1/3,result['precision'])
        self.assertEqual(1,result['good_rep_recall'])

    def test_missing_or_unassessable_events_do_not_disappear_from_recall_or_coverage(self):
        result=evaluate_rows([row([episode(),episode(1000),episode(2000,False)],
                                 [attempt(100,'UNASSESSABLE'),attempt(2100,'REJECTED')])])
        self.assertIsNone(result['precision'])
        self.assertEqual(0,result['good_rep_recall'])
        self.assertEqual(1/3,result['assessable_coverage'])
        self.assertEqual(2,result['counts']['false_negative'])

    def test_temporal_matching_does_not_use_correctness_or_verdicts(self):
        a=[episode(),episode(1000,False)]
        b=[attempt(),attempt(1100,'REJECTED')]
        baseline=match_events(a,b)
        for e in a:e['correct']=not e['correct']
        for e in b:e['verdict']='UNASSESSABLE'
        self.assertEqual(baseline,match_events(a,b))

    def test_event_outside_rep_boundaries_cannot_be_matched_by_nearest_time(self):
        matches,unmatched=match_events([episode()],[attempt(1200)])
        self.assertEqual({},matches)
        self.assertEqual([0],unmatched)

    def test_one_long_event_cannot_count_as_two_reps(self):
        matches,_=match_events([episode(),episode(1000)],
                              [{'started_ms':100,'completed_ms':1800,'verdict':'CORRECT'}])
        self.assertEqual(1,len(matches))


if __name__=='__main__':unittest.main()
