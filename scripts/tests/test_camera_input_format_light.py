import unittest
import test_camera_input_format as format_fixtures
from analyze_camera_input_format import MODEL
from analyze_camera_input_format_light import block_analysis


class LightObserverTests(unittest.TestCase):
    def data(self):
        s,r=format_fixtures.InputFormatTests().data()
        s.update(snapshot_export_outside_measurement=True,screen_export_outside_measurement=True,
                 snapshot_export_finished_ns=1000,screen_capture_ns=2000,screen_export_ns=1000,
                 post_bind_baseline_frames=0,post_screen_baseline_frames=1)
        before=[dict(r[0],capture_ns=10000+i*10000,analyzer_arrival_ns=10001+i*10000,result_ns=10002+i*10000) for i in range(4)]
        return s,before+r

    def test_export_placement_does_not_change_original_window_counts_or_clock_scope(self):
        s,r=self.data();o=block_analysis(s,r,[],[],MODEL)
        self.assertEqual(450,o['delivered_frames']);self.assertEqual(10,o['delivered_fps'])
        self.assertIsNone(o['p95_capture_to_result_ms']);self.assertEqual(0,o['readiness_points_earned'])

    def test_image_or_screen_work_inside_window_is_refused(self):
        for field in ['snapshot_export_finished_ns','screen_capture_ns']:
            s,r=self.data();s[field]=s['measurement_start_ns']+1
            with self.subTest(field=field),self.assertRaises(ValueError):block_analysis(s,r,[],[],MODEL)

    def test_missing_three_post_screen_results_is_refused(self):
        s,r=self.data();s['screen_capture_ns']=24000
        with self.assertRaises(ValueError):block_analysis(s,r,[],[],MODEL)
