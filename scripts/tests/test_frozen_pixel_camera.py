import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_frozen_pixel_camera import analyze


class FrozenPixelTests(unittest.TestCase):
    def data(self):
        summary=dict(schema='senpai-frozen-pixel-camera/1',generated_model_timestamps=True,model_input_is_actual_camera_frame=False)
        blocks=[]
        for case in ['room','positive']:
            for index,active in enumerate([False,True,True,False]):
                meta=dict(case_id=case,block_order=index+1,camera_active=active,model_pixel_sha256='a'*64,model_input_width=480,model_input_height=640)
                rows=[dict(index=i,warmup=i<8,generated_model_timestamp_ms=i*67,native_detect_ns=10_000_000,estimate_call_ns=15_000_000,owner_priority=-2,camera_capture_ns=1_000_000_000+i*100_000_000 if active else None,camera_conversion_ns=1000 if active else 0,estimate_owner_cpu_ns=2_000_000,estimate_process_cpu_ms=30,raw_status='BLIND',native_recreations=0,owner_tid=7) for i in range(32)]
                blocks.append(dict(metadata=meta,rows=rows,errors=[]))
        return summary,blocks
    def test_exact_input_comparison_is_never_camera_latency_or_accuracy(self):
        s,b=self.data();o=analyze(s,b);self.assertEqual(256,o['all_rows']);self.assertEqual(192,o['measured_rows']);self.assertIsNone(o['live_delivered_fps']);self.assertIsNone(o['capture_to_result_latency']);self.assertEqual(0,o['readiness_points_earned']);self.assertEqual(30,o['blocks'][0]['estimate_process_cpu']['mean_ms'])
    def test_pixel_hash_and_order_changes_are_refused(self):
        for key,value in [('model_pixel_sha256','b'*64),('camera_active',False),('block_order',4)]:
            s,b=self.data();b[1]['metadata'][key]=value
            with self.assertRaises(ValueError):analyze(s,b)
    def test_static_model_timestamp_and_camera_source_cannot_be_conflated(self):
        for fault in ['capture','timestamp','domain']:
            s,b=self.data()
            if fault=='capture':b[0]['rows'][0]['camera_capture_ns']=123
            elif fault=='timestamp':b[1]['rows'][8]['generated_model_timestamp_ms']=1234
            else:s['model_input_is_actual_camera_frame']=True
            with self.assertRaises(ValueError):analyze(s,b)
    def test_missing_owner_cpu_stays_unknown_and_worker_cpu_is_not_capped_at_wall(self):
        s,b=self.data();b[0]['rows'][10]['estimate_owner_cpu_ns']=None;o=analyze(s,b)
        self.assertIsNone(o['blocks'][0]['estimate_owner_cpu']);self.assertEqual(30,o['blocks'][0]['estimate_process_cpu']['mean_ms']);self.assertEqual(15,o['blocks'][0]['estimate_call']['mean_ms'])


if __name__=='__main__':unittest.main()
