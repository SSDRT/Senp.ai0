import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_live_camera_probe import analyze


class CameraProbeTests(unittest.TestCase):
    def data(self):
        start=1_000_000_000;end=start+60_000_000_000
        rows=[dict(capture_ns=start+i*100_000_000,analyzer_arrival_ns=start+i*100_000_000+5_000_000,result_ns=start+i*100_000_000+25_000_000,inference_ns=10_000_000,timestamp_source=1,model_sha256='a'*64,tracker_resets=0,native_recreations=0) for i in range(600)]
        s=dict(schema='senpai-live-camera-probe/1',real_camera_capture_measured=True,generated_replay_timestamps=False,requested_measurement_ms=60000,warmup_ms=30000,backpressure_strategy=0,frames_after_resume=13,resume_baseline_frames=10,measurement_start_ns=start,measurement_end_ns=end,model_sha256='a'*64,maximum_queue_capacity=1,api=35,device='test',compiler='qualified-rep-compiler/9')
        memory=[dict(elapsed_realtime_ns=start+i*5_000_000_000,total_pss_kib=256*1024) for i in range(13)]
        return s,rows,memory
    def test_fast_real_camera_probe_still_earns_no_ten_minute_points(self):
        s,r,m=self.data();out=analyze(s,r,m,[]);self.assertEqual(out['delivered_fps'],10);self.assertEqual(out['p95_capture_to_result_ms'],25);self.assertFalse(out['ten_minute_window']);self.assertEqual(out['readiness_points_earned'],0)
    def test_unknown_capture_clock_is_not_replaced_by_analyzer_or_native_duration(self):
        s,r,m=self.data();r[0]['timestamp_source']=0;out=analyze(s,r,m,[]);self.assertIsNone(out['p95_capture_to_result_ms']);self.assertIsNone(out['paired_processing']['p95_capture_to_result_ms']);self.assertEqual(out['p95_analyzer_to_result_ms'],20)
    def test_static_replay_changed_scope_and_failed_resume_are_refused(self):
        for fault in ['static','long','resume']:
            s,r,m=self.data()
            if fault=='static':s['generated_replay_timestamps']=True
            elif fault=='long':s['measurement_end_ns']=s['measurement_start_ns']+600_000_000_000
            else:s['frames_after_resume']=12
            with self.subTest(fault=fault),self.assertRaises(ValueError):analyze(s,r,m,[])


if __name__=='__main__':unittest.main()
