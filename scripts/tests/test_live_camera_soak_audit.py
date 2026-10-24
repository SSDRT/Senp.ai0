import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_live_camera_soak import summarize


class SoakAuditTests(unittest.TestCase):
    def fixture(self):
        start=1_000_000_000;end=start+600_000_000_000
        samples=[dict(capture_ns=start+i*100_000_000,analyzer_arrival_ns=start+i*100_000_000+5_000_000,
                      result_ns=start+i*100_000_000+25_000_000,inference_ns=10_000_000,timestamp_source=1,
                      model_sha256='a'*64,tracker_resets=0) for i in range(6000)]
        memory=[dict(elapsed_realtime_ns=start+i*5_000_000_000,total_pss_kib=256*1024) for i in range(121)]
        return samples,memory,start,end

    def test_supported_complete_clock_and_memory_run_earns_only_performance_points(self):
        rows,memory,start,end=self.fixture()
        r=summarize(rows,memory,start,end,'a'*64,1)
        self.assertEqual(10,r['readiness_points_earned']);self.assertEqual(25,r['p95_capture_to_result_ms'])
        self.assertEqual(20,r['p95_analyzer_to_result_ms']);self.assertEqual(10,r['delivered_fps'])

    def test_unknown_clock_cannot_be_substituted_with_fast_inference(self):
        rows,memory,start,end=self.fixture()
        rows[0]['timestamp_source']=0
        r=summarize(rows,memory,start,end,'a'*64,1)
        self.assertIsNone(r['p95_capture_to_result_ms']);self.assertEqual(0,r['readiness_points_earned'])
        self.assertEqual(10,r['p95_native_detect_ms'])

    def test_slow_processing_model_mismatch_and_overlapping_queue_fail(self):
        for fault in ['slow','model','overlap','duration']:
            rows,memory,start,end=self.fixture()
            if fault=='slow':rows=rows[::10]
            elif fault=='model':rows[0]['model_sha256']='b'*64
            elif fault=='overlap':rows[1]['analyzer_arrival_ns']=rows[0]['result_ns']-1
            else:end-=1
            self.assertEqual(0,summarize(rows,memory,start,end,'a'*64,1)['readiness_points_earned'],fault)

    def test_sparse_or_growing_memory_cannot_earn_memory_points(self):
        rows,memory,start,end=self.fixture()
        for changed in [memory[::4],[dict(m,total_pss_kib=(300 if i>95 else 256)*1024) for i,m in enumerate(memory)]]:
            r=summarize(rows,changed,start,end,'a'*64,1)
            self.assertEqual(5,r['readiness_points_earned']);self.assertFalse(r['memory_pass'])


if __name__=='__main__':unittest.main()
