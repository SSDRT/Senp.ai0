import copy,importlib.util,itertools,unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('probe',Path(__file__).resolve().parents[1]/'audit_live_delegate_probe.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)


class DelegateProbeTests(unittest.TestCase):
    def setUp(self):
        self.protocol=dict(api=35,abi='x86_64',fixture_sha256='f'*64,
            model_sha256=dict(FULL='a'*64,HEAVY='b'*64),native_parity_tolerance=1e-6)
        self.report=dict(schema='senpai-native-delegate-probe/1',api=35,abi='x86_64',fixture_sha256='f'*64,
            synthetic_diagnostic_timebase=True,real_camera_inference=False,warmup_frames=2,measured_frames=8,rows=[])
        for model,scene,delegate in itertools.product(['FULL','HEAVY'],['pose','blank'],['CPU','GPU']):
            frames=[]
            for i in range(10):
                points=[dict(id=n,image=[.5,.5,0.0],world=[.1,.2,.3],visibility=.8,presence=.9) for n in range(33)] if scene=='pose' else []
                frames.append(dict(index=i,diagnostic_timestamp_ms=i*67,warmup=i<2,total_call_ns=10000000,
                    inference_ns=8000000,actual_delegate=delegate,fallback_reason=None,raw_landmarks=points,native_recreations=0))
            self.report['rows'].append(dict(model=model,scene=scene,requested_delegate=delegate,creation_ns=10000000,
                model_sha256=self.protocol['model_sha256'][model],frames=frames))

    def test_static_stream_never_earns_live_performance_or_accuracy(self):
        result=probe.audit(self.report,self.protocol)
        self.assertEqual(0,result['performance_readiness_points']);self.assertIsNone(result['actual_live_rep_precision'])
        self.assertFalse(result['real_camera_measured']);self.assertFalse(result['production_changed'])

    def test_cpu_fallback_is_never_reported_as_GPU_acceleration(self):
        for row in self.report['rows']:
            if row['requested_delegate']=='GPU':
                for f in row['frames']:f.update(actual_delegate='CPU',fallback_reason='GPU initialization failed')
        result=probe.audit(self.report,self.protocol)
        self.assertTrue(all(not r['ratio_is_GPU_acceleration_evidence'] for r in result['comparisons']))
        self.report['rows'][1]['frames'][2]['fallback_reason']=None
        with self.assertRaises(ValueError):probe.audit(self.report,self.protocol)

    def test_changed_raw_coordinates_are_recorded_without_repair_or_wider_tolerance(self):
        self.report['rows'][1]['frames'][2]['raw_landmarks'][0]['world'][0]+=.02
        result=probe.audit(self.report,self.protocol)
        self.assertFalse(result['comparisons'][0]['within_existing_native_parity_tolerance'])
        self.assertAlmostEqual(.02,result['comparisons'][0]['maximum_raw_field_error']['world'])

    def test_incomplete_or_duplicated_conditions_fail(self):
        self.report['rows'][-1]=copy.deepcopy(self.report['rows'][0])
        with self.assertRaises(ValueError):probe.audit(self.report,self.protocol)

    def test_placeholders_cannot_be_observed_native_evidence(self):
        self.report['rows'][2]['frames'][0]['raw_landmarks']=copy.deepcopy(self.report['rows'][0]['frames'][0]['raw_landmarks'])
        with self.assertRaises(ValueError):probe.audit(self.report,self.protocol)

    def test_changed_identity_timebase_or_nonfinite_time_fails(self):
        for kind in ['model','time','nan']:
            changed=copy.deepcopy(self.report)
            if kind=='model':changed['rows'][0]['model_sha256']='0'*64
            if kind=='time':changed['rows'][0]['frames'][3]['diagnostic_timestamp_ms']=1
            if kind=='nan':changed['rows'][0]['frames'][3]['inference_ns']=float('nan')
            with self.assertRaises(ValueError):probe.audit(changed,self.protocol)


if __name__=='__main__':unittest.main()
