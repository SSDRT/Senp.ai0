import copy
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate_uiprmd_side_binding import compare


def replay(mode):
    rows = []
    for subject in ['05', '06', '07']:
        for movement in ['m01', 'm07']:
            for correct in [True, False]:
                row = dict(subject=subject, movement=movement, correct=correct, exercise_id=movement,
                           side='LEFT' if movement == 'm01' else 'RIGHT', source_member='source',
                           source_sha256='source', reference_sha256='reference', reference_key='key',
                           reference_subject='01', status='REPLAYED', attempts=[],
                           episodes=[dict(episode=i, start_ms=i*1000, end_ms=i*1000+900, correct=correct) for i in range(10)])
                if mode == 'causal' and movement == 'm07':
                    row.update(research_variant='standing-unilateral-shoulder-abduction-interchangeable-side', binding_events=[])
                rows.append(row)
    return dict(schema='uiprmd-side-binding-replay/1', scope='validation', mapping_mode=mode,
                compiler='qualified-rep-compiler/5', candidate_protocol_sha256='protocol',
                final_test_trajectories_opened=False, measurement_domain='skeletal',
                raw_landmark_network_trained=False, manifest_sha256='manifest', rows=rows)


class SideBindingEvaluationTests(unittest.TestCase):
    def test_all_missing_events_keep_full_denominators_and_no_frozen_score_replacement(self):
        result = compare(replay('fixed'), replay('causal'), 'validation', 'protocol')
        self.assertEqual(120, result['results']['causal_variant']['counts']['total_reps'])
        self.assertEqual(0, result['results']['causal_variant']['good_rep_recall'])
        self.assertFalse(result['frozen_variant_score_replaced'])
        self.assertEqual(0, result['readiness_points_earned'])

    def test_final_split_duplicate_and_missing_reps_are_rejected(self):
        for kind in ['final', 'duplicate', 'rep']:
            causal = replay('causal')
            if kind == 'final': causal['rows'][0]['subject'] = '08'
            elif kind == 'duplicate': causal['rows'][-1] = copy.deepcopy(causal['rows'][0])
            else: causal['rows'][0]['episodes'].pop()
            with self.assertRaises(ValueError): compare(replay('fixed'), causal, 'validation', 'protocol')
        with self.assertRaises(ValueError): compare(replay('fixed'), replay('causal'), 'test', 'protocol')

    def test_source_reference_labels_or_untargeted_exercise_cannot_change(self):
        for key in ['source_sha256', 'reference_subject', 'episodes', 'attempts']:
            causal = replay('causal')
            if key == 'episodes': causal['rows'][0][key][0]['correct'] = False
            elif key == 'attempts': causal['rows'][0][key] = [{'invented': True}]
            else: causal['rows'][0][key] = 'other'
            with self.assertRaises(ValueError): compare(replay('fixed'), causal, 'validation', 'protocol')

    def test_unbound_attempts_and_per_rep_rebinding_are_rejected(self):
        for kind in ['unbound', 'rebound', 'prebinding']:
            causal = replay('causal')
            row = causal['rows'][2]
            event = dict(state='LOCKED', side='LEFT', locked_ms=200, timestamp_ms=200)
            if kind == 'unbound': row['attempts'] = [dict(started_ms=100, completed_ms=900, verdict='CORRECT')]
            elif kind == 'rebound': row['binding_events'] = [event, dict(event, side='RIGHT')]
            else:
                row['binding_events'] = [event]
                row['attempts'] = [dict(started_ms=0, completed_ms=100, verdict='CORRECT')]
            with self.assertRaises(ValueError): compare(replay('fixed'), causal, 'validation', 'protocol')


if __name__ == '__main__':
    unittest.main()
