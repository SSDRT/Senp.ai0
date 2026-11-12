import copy
import unittest

from analyze_camera_input_format import block_analysis,compare,MODEL,ORDER


class InputFormatTests(unittest.TestCase):
    def data(self):
        start=1_000_000_000
        s=dict(block=1,rgba_mode=True,requested_input_format='RGBA',actual_mode='REBOUND_FORMAT_PREVIEW_AND_ANALYSIS',warmup_ms=15000,requested_measurement_ms=45000,
            measurement_start_ns=start,measurement_end_ns=start+45_000_000_000,snapshot_saved=True,maximum_queue_capacity=1,
            preview_views_start=['android.view.SurfaceView'],preview_views_end=['android.view.SurfaceView'],snapshot_export_ns=1000,screen_export_ns=1000)
        rows=[dict(capture_ns=start+i*100_000_000,analyzer_arrival_ns=start+i*100_000_000+1000,result_ns=start+i*100_000_000+1_000_000,inference_ns=1000,
            timestamp_source=0,model_sha256=MODEL,tracker_resets=0,native_recreations=0,input_format=1,input_width=640,input_height=480,input_rotation=90,
            width=480,height=640,input_full_crop=1,input_one_pixel_shift=0,input_planes=1,input_row0=2560,input_pixel0=4,input_row1=-1,input_pixel1=-1,input_row2=-1,input_pixel2=-1,
            callback_thread_priority=-2,raw_status='BLIND') for i in range(450)]
        return s,rows

    def test_actual_fps_keeps_unknown_capture_clock_and_no_short_soak_credit(self):
        s,r=self.data();o=block_analysis(s,r,[],[],MODEL)
        self.assertEqual(10,o['delivered_fps']);self.assertIsNone(o['p95_capture_to_result_ms']);self.assertEqual(0,o['readiness_points_earned'])

    def test_requested_yuv_must_be_observed_and_match_fidelity_layout(self):
        s,r=self.data();s.update(rgba_mode=False,requested_input_format='YUV')
        with self.assertRaises(ValueError):block_analysis(s,r,[],[],MODEL)
        for row in r:row.update(input_format=35,input_planes=3,input_row0=640,input_pixel0=1,input_row1=320,input_pixel1=1,input_row2=320,input_pixel2=1)
        self.assertEqual([35],block_analysis(s,r,[],[],MODEL)['observed_source_formats'])
        r[0]['input_pixel1']=2
        with self.assertRaises(ValueError):block_analysis(s,r,[],[],MODEL)

    def test_scope_rotation_quirk_and_owner_cannot_drift(self):
        for key,value in [('input_rotation',0),('input_one_pixel_shift',1),('input_full_crop',0),('callback_thread_priority',0)]:
            s,r=self.data();r[0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):block_analysis(s,r,[],[],MODEL)

    def test_prior_gate_and_constant_observed_preview_are_required(self):
        s,r=self.data();o=block_analysis(s,r,[],[],MODEL)
        blocks=[dict(copy.deepcopy(o),requested_input_format=m) for m in ORDER]
        gate=dict(pixel_fidelity_gate_pass=True,pairs=6)
        self.assertEqual(1,compare(blocks,gate)['yuv_to_rgba_mean_fps_ratio'])
        with self.assertRaises(ValueError):compare(blocks,dict(pixel_fidelity_gate_pass=False,pairs=6))
        blocks[1]['preview_view_start']='TextureView'
        with self.assertRaises(ValueError):compare(blocks,gate)
