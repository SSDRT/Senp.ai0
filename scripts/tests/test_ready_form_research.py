import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from audit_ready_form_research import compare


def control_report():
    rows=[]
    for subject in ['01','02','03','04']:
        for movement in ['m01','m07']:
            for correct in [True,False]:
                episodes=[dict(episode=i+1,start_ms=i*2000,end_ms=i*2000+1500,correct=correct) for i in range(10)]
                attempts=[dict(started_ms=e['start_ms']+200,completed_ms=e['end_ms'],verdict='ACCEPTABLE' if correct else 'REJECTED') for e in episodes]
                rows.append(dict(subject=subject,movement=movement,correct=correct,episodes=episodes,attempts=attempts,
                    reference_subject='02' if subject=='01' else '01',source_sha256='a'*64,reference_sha256='b'*64,
                    source_member=f'{subject}/{movement}/{correct}',exercise_id=movement,side='LEFT'))
    return dict(rows=rows)


class ReadyFormResearchTests(unittest.TestCase):
    def test_original_denominators_and_one_to_one_good_loss_are_retained(self):
        baseline=control_report();changed=copy.deepcopy(baseline)
        changed['rows'][0]['attempts'][0]['verdict']='UNASSESSABLE'
        result=compare(baseline,changed)
        self.assertEqual(result['before']['counts']['total_reps'],160)
        self.assertEqual(result['after']['counts']['total_reps'],160)
        self.assertEqual(len(result['previously_counted_correct_lost']),1)
        self.assertFalse(result['development_promotion_gate_passed'])

    def test_new_bad_count_blocks_promotion_even_with_unchanged_good_recall(self):
        baseline=control_report();changed=copy.deepcopy(baseline)
        changed['rows'][1]['attempts'][0]['verdict']='ACCEPTABLE'
        result=compare(baseline,changed)
        self.assertEqual(len(result['new_incorrect_counts']),1)
        self.assertFalse(result['development_promotion_gate_passed'])

    def test_changed_sources_labels_sides_references_duplicates_and_final_subjects_fail_closed(self):
        baseline=control_report()
        for kind in ['source','reference','side','label','duplicate','final']:
            changed=copy.deepcopy(baseline)
            if kind=='source':changed['rows'][0]['source_sha256']='c'*64
            elif kind=='reference':changed['rows'][0]['reference_subject']='04'
            elif kind=='side':changed['rows'][0]['side']='RIGHT'
            elif kind=='label':changed['rows'][0]['episodes'][0]['correct']=False
            elif kind=='duplicate':changed['rows'][1]=copy.deepcopy(changed['rows'][0])
            else:changed['rows'][0]['subject']='08'
            with self.assertRaises(ValueError):compare(baseline,changed)


if __name__=='__main__':unittest.main()
