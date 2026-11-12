import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_uiprmd_frontal_audit import episode_inputs,observed_span,validate_manifest


class FrontalAuditTests(unittest.TestCase):
    def manifest(self):
        return dict(schema='uiprmd-frontal-audit/1',compiler='qualified-rep-compiler/9',scope='development',protocol_sha256='abc',validation_accessed=False,final_test_accessed=False,production_changed=False,records=[dict(subject=s,movement=m,correct=c) for s in ['01','02','03','04'] for m in ['m01','m07'] for c in [True,False]])
    def test_full_development_only_manifest(self):
        validate_manifest(self.manifest(),'abc')
        for change in [dict(scope='validation'),dict(final_test_accessed=True),dict(compiler='future'),dict(protocol_sha256='changed')]:
            with self.assertRaises(PermissionError):validate_manifest(dict(self.manifest(),**change),'abc')
    def test_missing_duplicate_or_heldout_subject_refused(self):
        for records in [self.manifest()['records'][:-1],self.manifest()['records'][:-1]+[self.manifest()['records'][0]]]:
            with self.assertRaises(PermissionError):validate_manifest(dict(self.manifest(),records=records),'abc')
        m=self.manifest();m['records'][0]['subject']='08'
        with self.assertRaises(PermissionError):validate_manifest(m,'abc')
    def test_observed_span_never_bridges_a_missing_sample_or_time_gap(self):
        rows=[dict(timestamp_ms=t,valid=v) for t,v in [(0,True),(10,True),(20,False),(30,True),(50,True),(60,True)]]
        self.assertEqual(10,observed_span(rows,lambda x:x['valid']))
        with self.assertRaises(ValueError):observed_span(rows+rows[:1],lambda x:True)
    def record(self):
        frames=[dict(timestamp_ms=i*10,stage='SYNCHRONIZING',progress=.9,critical_values={'angle.right_shoulder':120,'angle.right_elbow':170}) for i in range(6)]
        return dict(frames=frames,ready_progress=.1,required_progress=.85)
    def test_episode_numeric_missing_is_kept_without_inventing_an_attempt(self):
        r=self.record();r['frames'][2]['critical_values']['angle.right_shoulder']=None;r['frames'][2]['progress']=None
        result=episode_inputs(r,dict(start_ms=0,end_ms=50));self.assertEqual(6,result['original_input_frames']);self.assertEqual(1,result['critical_numeric_missing_frames']);self.assertEqual(20,result['observed_far_input_span_ms']);self.assertTrue(result['these_input_spans_are_not_engine_confirmations']);self.assertNotIn('verdict',result)
    def test_exact_original_bounds_and_prelude_are_separate(self):
        r=self.record();result=episode_inputs(r,dict(start_ms=20,end_ms=50));self.assertEqual(4,result['original_input_frames']);self.assertEqual({'SYNCHRONIZING':2},result['prelude_1000ms_stage_counts'])
        for episode in [dict(start_ms=-1,end_ms=20),dict(start_ms=30,end_ms=20),dict(start_ms=0,end_ms=60)]:
            with self.assertRaises(ValueError):episode_inputs(r,episode)
        r['frames'][1]['timestamp_ms']=11
        with self.assertRaises(ValueError):episode_inputs(r,dict(start_ms=0,end_ms=50))


if __name__=='__main__':unittest.main()
