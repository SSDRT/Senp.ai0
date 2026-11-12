import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from profile_live_processing_stages import original_soak,native_profile


class StageProfileTests(unittest.TestCase):
    def data(self):
        rows=[dict(capture_ns=1,analyzer_arrival_ns=100,result_ns=110,inference_ns=9,timestamp_source=0,model_sha256='a'*64),dict(capture_ns=2,analyzer_arrival_ns=200,result_ns=270,inference_ns=1,timestamp_source=0,model_sha256='a'*64)]
        return dict(measurement_start_ns=100,measurement_end_ns=300,model_sha256='a'*64,compiler='historical'),rows
    def test_paired_residual_is_not_difference_of_percentiles_and_unknown_clock_stays_unknown(self):
        s,r=self.data();out=original_soak(s,r);self.assertEqual(69/1e6,out['unattributed_analyzer']['p95_ms']);self.assertNotEqual(out['analyzer_total']['p95_ms']-out['native_detect']['p95_ms'],out['unattributed_analyzer']['p95_ms']);self.assertIsNone(out['p95_capture_to_result_ms']);self.assertEqual(0,out['readiness_points_earned'])
    def test_invalid_nested_durations_overlap_and_model_mismatch_are_rejected(self):
        for fault in ['nested','overlap','model']:
            s,r=self.data()
            if fault=='nested':r[0]['inference_ns']=11
            elif fault=='overlap':r[1]['analyzer_arrival_ns']=109
            else:r[1]['model_sha256']='b'*64
            with self.subTest(fault=fault),self.assertRaises(ValueError):original_soak(s,r)
    def test_fast_static_capacity_cannot_earn_live_fps_or_latency_credit(self):
        rows=[dict(index=i,warmup=i<8,generated_model_timestamp_ms=i*67,input_preparation_ns=1,estimate_call_ns=5,native_detect_ns=4,other_estimator_ns=1,generic_update_ns=1,qualified_update_ns=2,processor_order='generic-first' if i%2==0 else 'qualified-first',raw_status='BLIND',native_recreation_count=0) for i in range(32)]
        p=dict(schema='senpai-live-stage-profile/1',duration_clock='System.nanoTime',real_camera_capture_measured=False,generated_replay_timestamps=True,api=35,device='test',compiler='test',model_sha256='a'*64,cases=[dict(case=dict(id=str(i)),rows=rows,prepared_width=640,prepared_height=480) for i in range(4)])
        r=native_profile(p);self.assertIsNone(r['live_delivered_fps']);self.assertIsNone(r['p95_capture_to_result_ms']);self.assertEqual(0,r['readiness_points_earned'])
        p['cases'][0]['rows']=rows[:-1]
        with self.assertRaises(ValueError):native_profile(p)


if __name__=='__main__':unittest.main()
