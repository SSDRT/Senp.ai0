import unittest

from analyze_frozen_detect_stages import analyze, CASES, EXPECTED, ORDER, STAGES, MODEL_SHA


class FrozenDetectStageTests(unittest.TestCase):
    def data(self):
        summary=dict(schema='senpai-frozen-detect-stages/1',generated_model_timestamps=True,model_input_is_actual_camera_frame=False,
                     model_sha256=MODEL_SHA,compiler='qualified-rep-compiler/9')
        blocks=[]
        for case in CASES:
            for n,active in enumerate(ORDER):
                h,w,height=EXPECTED[case]
                meta=dict(case_id=case,block_order=n+1,camera_active=active,model_pixel_sha256=h,model_input_width=w,model_input_height=height)
                rows=[]
                for i in range(32):
                    row=dict(index=i,warmup=i<8,generated_model_timestamp_ms=i*67,native_result_timestamp_ms=i*67,
                        owner_priority=-2,owner_tid=7,native_graph_identity=n,body_owner_cpu_ns=2_000_000,body_process_cpu_ms=30,
                        camera_capture_ns=i*100_000_000+1 if active else None,camera_conversion_ns=1000 if active else 0,native_pose_count=0)
                    row.update({k:1_000_000 for k in STAGES});row['native_task_call_ns']=10_000_000 if active else 5_000_000
                    row['partitioned_body_ns']=sum(row[k] for k in STAGES);rows.append(row)
                blocks.append(dict(metadata=meta,rows=rows,errors=[]))
        return summary,blocks

    def test_partitioned_static_costs_never_become_camera_fps_or_accuracy(self):
        s,b=self.data();o=analyze(s,b)
        self.assertEqual(2,o['comparisons'][0]['active_to_quiet_mean_ratio']['native_task_call_ns'])
        self.assertEqual(192,o['measured_rows']);self.assertIsNone(o['capture_to_result_latency'])
        self.assertIsNone(o['live_delivered_fps']);self.assertIsNone(o['human_rep_precision']);self.assertEqual(0,o['readiness_points_earned'])

    def test_missing_double_counted_or_negative_stage_is_refused(self):
        for field,value in [('partitioned_body_ns',0),('owned_copy_ns',-1),('mpimage_close_ns',1.5)]:
            s,b=self.data();b[0]['rows'][8][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):analyze(s,b)

    def test_pixel_order_and_generated_time_cannot_drift(self):
        for fault in ['pixels','order','returned_time']:
            s,b=self.data()
            if fault=='pixels':b[1]['metadata']['model_pixel_sha256']='0'*64
            elif fault=='order':b[1]['metadata']['camera_active']=False
            else:b[1]['rows'][8]['native_result_timestamp_ms']=10
            with self.subTest(fault=fault),self.assertRaises(ValueError):analyze(s,b)

    def test_graph_and_owner_are_fixed_and_only_active_blocks_have_camera_sources(self):
        for key,value in [('native_graph_identity',99),('owner_tid',99),('camera_capture_ns',123)]:
            s,b=self.data();b[0]['rows'][8][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):analyze(s,b)

    def test_absent_owner_cpu_is_not_filled_or_called_native_worker_time(self):
        s,b=self.data();b[0]['rows'][8]['body_owner_cpu_ns']=None;o=analyze(s,b)
        self.assertIsNone(o['blocks'][0]['body_owner_cpu']);self.assertTrue(o['owner_CPU_excludes_native_workers'])
        self.assertEqual(30,o['blocks'][0]['body_process_cpu']['mean_ms'])
