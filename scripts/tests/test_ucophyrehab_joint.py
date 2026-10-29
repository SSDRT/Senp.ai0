import io
import json
import unittest

from audit_ucophyrehab_joint import select_joint_frames, subject_or_exercise


class JointIntakeTests(unittest.TestCase):
    def read(self, data):
        return select_joint_frames(io.BytesIO(json.dumps(data).encode()))

    def record(self, folder=1, exercise=1):
        return dict(folder=folder, exercise=exercise, frames=[dict(id=0, joints={'angle': 0, 'points': [None, 0, -1.2]})])

    def test_only_frozen_subject_exercise_frames_are_constructed(self):
        records = [self.record(22), self.record(1, 5), self.record('01','01'), self.record(27)]
        for r in [records[0],records[1],records[3]]:r['frames']=[{'secret_trajectory': 'sealed'}]
        result = self.read({'data': records})
        self.assertEqual(2, result['selected_record_index'])
        self.assertEqual(4, len(result['metadata_roster']))
        self.assertEqual(1, len(result['frames']))
        self.assertNotIn('secret_trajectory', str(result))
        self.assertFalse(result['other_record_trajectory_objects_constructed'])

    def test_record_identity_cannot_be_reconstructed_after_frames(self):
        with self.assertRaisesRegex(ValueError, 'Identity must precede'):
            self.read({'data': [dict(frames=[{'id': 1}], folder=1, exercise=1)]})

    def test_missing_selected_subject_is_not_replaced_by_available_data(self):
        with self.assertRaises(ValueError):self.read({'data': [self.record(2)]})

    def test_duplicate_selected_recordings_are_not_merged(self):
        with self.assertRaisesRegex(ValueError, 'duplicate selected'):
            self.read({'data': [self.record(), self.record()]})

    def test_zeros_nulls_and_original_frame_ids_are_preserved(self):
        frame = self.read({'data': [self.record()]})['frames'][0]
        self.assertEqual(0, frame['id'])
        self.assertEqual(0, frame['joints']['angle'])
        self.assertIsNone(frame['joints']['points'][0])
        self.assertNotIn('timestamp_ms', frame)
        self.assertNotIn('confidence', frame)

    def test_numeric_ids_do_not_accept_boolean_fraction_or_guessed_prefix(self):
        for value in [True, False, -1, 0, 'S01', 1.5, '', None]:
            with self.subTest(value=value), self.assertRaises(ValueError):subject_or_exercise(value)

    def test_unknown_root_or_missing_identity_is_not_guessed(self):
        for data in [[self.record()], {'data': [{'frames': []}]}, {'other': [self.record()]}]:
            with self.subTest(data=data), self.assertRaises(ValueError):self.read(data)
