import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_camera_preview_mode import block_analysis,compare,view_kind


class PreviewModeTests(unittest.TestCase):
    def data(self):
        start=1_000_000_000;s=dict(block=1,compatible_mode=True,requested_implementation='COMPATIBLE',actual_mode='REBOUND_PREVIEW_AND_ANALYSIS',measurement_start_ns=start,measurement_end_ns=start+45_000_000_000,warmup_ms=15000,requested_measurement_ms=45000,snapshot_saved=True,maximum_queue_capacity=1,preview_views_start=['android.view.TextureView'],preview_views_end=['android.view.TextureView'],snapshot_export_ns=1000,screen_capture_ns=start+25_000_000_000,screen_export_ns=2000,screen_saved=True)
        rows=[dict(capture_ns=start+i*100_000_000,analyzer_arrival_ns=start+i*100_000_000+1_000_000,result_ns=start+i*100_000_000+10_000_000,inference_ns=5_000_000,timestamp_source=0,model_sha256='a'*64,tracker_resets=0,native_recreations=0,width=480,height=640,raw_status='BLIND') for i in range(450)]
        return s,rows
    def test_fast_short_actual_camera_still_earns_no_credit_and_unknown_latency(self):
        s,r=self.data();o=block_analysis(s,r,[],[],'a'*64);self.assertEqual(10,o['delivered_fps']);self.assertIsNone(o['p95_capture_to_result_ms']);self.assertEqual(0,o['readiness_points_earned']);self.assertEqual('TextureView',o['actual_view_start'])
    def test_performance_request_does_not_prove_surfaceview_when_it_falls_back(self):
        s,r=self.data();o=block_analysis(s,r,[],[],'a'*64);blocks=[dict(copy.deepcopy(o),requested_implementation=m) for m in ['COMPATIBLE','PERFORMANCE','PERFORMANCE','COMPATIBLE']]
        self.assertFalse(compare(blocks)['actual_surface_contrast_observed'])
        for b in blocks[1:3]:b.update(actual_view_start='SurfaceView',actual_view_end='SurfaceView')
        self.assertTrue(compare(blocks)['actual_surface_contrast_observed'])
    def test_missing_or_mixed_surface_is_explicit(self):
        self.assertEqual('MIXED_OR_NOT_OBSERVED',view_kind(['android.widget.FrameLayout']))
        self.assertEqual('MIXED_OR_NOT_OBSERVED',view_kind(['android.view.SurfaceView','android.view.TextureView']))
    def test_wrong_mode_window_or_order_cannot_silently_pass(self):
        s,r=self.data();bad=copy.deepcopy(s);bad['requested_implementation']='PERFORMANCE'
        with self.assertRaises(ValueError):block_analysis(bad,r,[],[],'a'*64)
        bad=copy.deepcopy(s);bad['requested_measurement_ms']=600000
        with self.assertRaises(ValueError):block_analysis(bad,r,[],[],'a'*64)
        o=block_analysis(s,r,[],[],'a'*64)
        with self.assertRaises(ValueError):compare([o]*4)


if __name__=='__main__':unittest.main()
