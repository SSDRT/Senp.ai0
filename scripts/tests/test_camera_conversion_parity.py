import unittest

from analyze_camera_conversion_parity import analyze


class ConversionParityTests(unittest.TestCase):
    def data(self):
        rows=[]
        for i in range(6):
            rows.append(dict(index=i,source_capture_ns=1000+i,source_format=35,source_width=640,source_height=480,rotation_degrees=90,
                prepared_width=480,prepared_height=640,total_pixels=307200,different_pixels=0,maximum_channel_difference=0,absolute_channel_difference_sum=0,
                direct_argb_sha256='a'*64,via_rgba_argb_sha256='a'*64,actual_one_pixel_shift_enabled=False,source_plane_bytes_unchanged=True,
                source_crop='[0,0][640,480]',source_planes=[dict(index=n) for n in range(3)]))
        return dict(schema='senpai-camerax-conversion-parity/1',requested_pairs=6,no_pose_inference_during_pairs=True,production_changed=False,rows=rows)

    def test_bit_exact_gate_is_not_fps_accuracy_or_promotion(self):
        o=analyze(self.data(),[])
        self.assertTrue(o['pixel_fidelity_gate_pass']);self.assertFalse(o['production_optimization_promoted'])
        self.assertIsNone(o['live_fps']);self.assertIsNone(o['human_rep_precision']);self.assertEqual(0,o['readiness_points_earned'])

    def test_even_one_changed_channel_is_retained_and_stops_followup(self):
        s=self.data();s['rows'][4].update(different_pixels=1,maximum_channel_difference=1,absolute_channel_difference_sum=1,via_rgba_argb_sha256='b'*64)
        o=analyze(s,[])
        self.assertFalse(o['pixel_fidelity_gate_pass']);self.assertEqual(6,len(o['rows']));self.assertEqual('STOP_BEFORE_PERFORMANCE_OR_PROMOTION',o['decision'])

    def test_device_shift_crop_or_source_mutation_cannot_be_silently_accepted(self):
        for key,value in [('actual_one_pixel_shift_enabled',True),('source_crop','[1,0][640,480]'),('source_plane_bytes_unchanged',False)]:
            s=self.data();s['rows'][2][key]=value
            with self.subTest(key=key):self.assertFalse(analyze(s,[])['pixel_fidelity_gate_pass'])

    def test_partial_or_duplicate_frames_do_not_make_six_independent_pairs(self):
        for fault in ['partial','duplicate','format','rotation']:
            s=self.data()
            if fault=='partial':s['rows'].pop()
            elif fault=='duplicate':s['rows'][1]['source_capture_ns']=s['rows'][0]['source_capture_ns']
            elif fault=='format':s['rows'][0]['source_format']=1
            else:s['rows'][0]['rotation_degrees']=0
            with self.subTest(fault=fault),self.assertRaises(ValueError):analyze(s,[])

    def test_false_zero_disagreement_and_missing_original_planes_are_refused(self):
        for fault in ['hash','planes','negative']:
            s=self.data()
            if fault=='hash':s['rows'][0]['via_rgba_argb_sha256']='b'*64
            elif fault=='planes':s['rows'][0]['source_planes'].pop()
            else:s['rows'][0]['absolute_channel_difference_sum']=-1
            with self.subTest(fault=fault),self.assertRaises(ValueError):analyze(s,[])
