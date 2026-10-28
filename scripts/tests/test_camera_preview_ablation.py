import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_camera_preview_ablation import block_analysis,compare_blocks


class PreviewAblationTests(unittest.TestCase):
    def data(self):
        start=1_000_000_000
        rows=[dict(capture_ns=start+i*100_000_000,analyzer_arrival_ns=start+i*100_000_000+1_000_000,result_ns=start+i*100_000_000+10_000_000,inference_ns=5_000_000,timestamp_source=0,model_sha256='a'*64,tracker_resets=0,native_recreations=0,callback_thread_cpu_ns=10_000_000+i*20_000_000,callback_tid=7,callback_thread_priority=0,width=480,height=640,raw_status='BLIND') for i in range(450)]
        summary=dict(block=1,requested_preview=True,actual_mode='UNCHANGED_ACTIVITY_PREVIEW_AND_ANALYSIS',measurement_start_ns=start,measurement_end_ns=start+45_000_000_000,warmup_ms=15000,requested_measurement_ms=45000,snapshot_saved=True,snapshot_export_ns=1000,maximum_queue_capacity=1)
        return summary,rows
    def test_unknown_clock_and_fast_short_window_never_earn_points(self):
        s,r=self.data();o=block_analysis(s,r,[],[],'a'*64)
        self.assertIsNone(o['p95_capture_to_result_ms']);self.assertEqual(0,o['readiness_points_earned']);self.assertEqual(10,o['delivered_fps']);self.assertEqual(4,o['paired_unattributed_analyzer_p95_ms'])
        self.assertEqual(20,o['callback_interval_thread_cpu_p95_ms']);self.assertEqual(100,o['callback_interval_wall_p95_ms'])
    def test_thread_change_or_unsupported_cpu_stays_unattributed(self):
        for key,value in [('callback_tid',9),('callback_thread_cpu_ns',0)]:
            s,r=self.data();r[10][key]=value;o=block_analysis(s,r,[],[],'a'*64)
            self.assertFalse(o['callback_interval_cpu_supported']);self.assertIsNone(o['callback_interval_thread_cpu_total_ms'])
    def test_binding_and_window_cannot_change_silently(self):
        for key,value in [('actual_mode','REBOUND_OWNED_ANALYSIS_ONLY'),('requested_measurement_ms',600000),('warmup_ms',0)]:
            s,r=self.data();s[key]=value
            with self.assertRaises(ValueError):block_analysis(s,r,[],[],'a'*64)
    def test_all_four_blocks_required_and_changed_dimensions_reported(self):
        s,r=self.data();base=block_analysis(s,r,[],[],'a'*64);blocks=[dict(copy.deepcopy(base),requested_preview=v) for v in [True,False,False,True]]
        o=compare_blocks(blocks);self.assertTrue(o['all_blocks_same_dimensions']);self.assertFalse(o['identical_source_frames_proven']);self.assertEqual(0,o['readiness_points_earned'])
        blocks[1]['dimensions']=[(640,480)];self.assertFalse(compare_blocks(blocks)['all_blocks_same_dimensions'])
        with self.assertRaises(ValueError):compare_blocks(blocks[:3])


if __name__=='__main__':unittest.main()
