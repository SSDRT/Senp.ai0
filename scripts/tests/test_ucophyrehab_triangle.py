import copy
import unittest

from audit_ucophyrehab_triangle import coordinates, recorded_number, summarize


def frame(fid=5):
    return dict(id=fid,joints={'l_hip':dict(x='0',y='0',z='0'),
        'l_knee':dict(x='1',y='0',z='0'),'l_ankle':dict(x='1',y='1',z='0'),'angle':90})


class TriangleConformanceTests(unittest.TestCase):
    def test_numeric_strings_are_parsed_without_locale_units_or_nonfinite_repair(self):
        self.assertEqual(-.0125,recorded_number(' -1.25e-2 '))
        for v in ['1,25','90deg','NaN','Infinity',True,{},'1e9999']:
            with self.subTest(v=v):self.assertIsNone(recorded_number(v))

    def test_measured_coordinate_origin_is_not_automatically_missing(self):
        self.assertEqual([0.,0.,0.],coordinates(frame())[0])

    def test_missing_coordinate_is_not_filled_from_neighboring_frame(self):
        f=frame();del f['joints']['l_knee']['z']
        self.assertIsNone(coordinates(f))
        rows,stats=summarize([f])
        self.assertIsNone(rows[0]['triangle_at_l_knee'])
        self.assertEqual(0,stats['l_knee']['compared_frames'])

    def test_all_vertices_reported_with_original_frame_id_and_no_time_creation(self):
        rows,stats=summarize([frame()])
        self.assertEqual({'l_hip','l_knee','l_ankle'},set(stats))
        self.assertEqual(0,stats['l_knee']['mean_absolute_difference_degrees'])
        self.assertEqual(45,stats['l_hip']['mean_absolute_difference_degrees'])
        self.assertEqual(5,rows[0]['source_frame_id'])
        self.assertNotIn('timestamp_ms',rows[0])

    def test_duplicate_or_reversed_frame_ids_are_refused_without_resampling(self):
        for ids in [[5,5],[6,5]]:
            with self.subTest(ids=ids),self.assertRaises(ValueError):summarize([frame(i) for i in ids])

    def test_prefix_and_gaps_are_not_filled_and_input_remains_original(self):
        data=[frame(5),frame(8)];before=copy.deepcopy(data)
        rows,_=summarize(data)
        self.assertEqual([5,8],[r['source_frame_id'] for r in rows])
        self.assertEqual(before,data)
