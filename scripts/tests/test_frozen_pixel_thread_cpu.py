import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from analyze_frozen_pixel_thread_cpu import parse_stat,census,interval


def line(tid=7,name='worker (with ) spaces)',user=10,system=3,start=100):
    fields=['S']+['0']*19;fields[11]=str(user);fields[12]=str(system);fields[19]=str(start)
    return f'{tid} ({name}) '+' '.join(fields)+'\n'


class ThreadCpuTests(unittest.TestCase):
    def snapshots(self):
        a=dict(scan_start_ns=1,scan_end_ns=3,enumeration_error=None,threads=[dict(tid='7',raw_stat=line())])
        b=dict(scan_start_ns=30,scan_end_ns=34,enumeration_error=None,threads=[dict(tid='7',raw_stat=line(user=12,system=4))]);return a,b
    def test_original_comm_parentheses_and_clock_tick_fields_are_not_split_as_columns(self):
        v=parse_stat(line(),'7');self.assertEqual('worker (with ) spaces)',v['name']);self.assertEqual(10,v['user_ticks']);self.assertEqual(3,v['system_ticks']);self.assertEqual(100,v['start_ticks'])
    def test_stable_identity_uses_user_plus_system_ticks_and_explicit_hz(self):
        a,b=self.snapshots();v=interval(a,b,100,5,25);self.assertEqual(30,v['matched'][0]['cpu_ms']);self.assertEqual(6,v['scan_overhead_ns']);self.assertEqual(33,v['bracket_ns'])
        with self.assertRaises(ValueError):interval(a,b,0,5,25)
    def test_recycled_tid_is_unknown_instead_of_new_cpu_work(self):
        a,b=self.snapshots();b['threads'][0]['raw_stat']=line(user=1,start=200);v=interval(a,b,100,5,25);self.assertEqual([7],v['reused']);self.assertEqual([],v['matched'])
    def test_unreadable_and_exited_threads_remain_explicit(self):
        a,b=self.snapshots();b['threads']=[dict(tid='8',read_error='raced exit')];v=interval(a,b,100,5,25);self.assertEqual([7],v['exited']);self.assertEqual([8],v['unreadable_after']);self.assertEqual([],v['matched'])
    def test_invalid_bounds_counter_and_duplicate_identity_are_refused(self):
        a,b=self.snapshots()
        with self.assertRaises(ValueError):interval(a,b,100,2,25)
        b['threads'][0]['raw_stat']=line(user=1)
        with self.assertRaises(ValueError):interval(a,b,100,5,25)
        a['threads']*=2
        with self.assertRaises(ValueError):census(a)
    def test_wrong_tid_and_truncated_stat_cannot_produce_fake_cpu_values(self):
        with self.assertRaises(ValueError):parse_stat(line(),8)
        with self.assertRaises(ValueError):parse_stat('7 (missing) S 0',7)
        a,b=self.snapshots();b['enumeration_error']='denied';self.assertFalse(interval(a,b,100,5,25)['enumeration_supported'])

    def test_read_failure_is_not_mislabeled_as_thread_birth_or_exit(self):
        a,b=self.snapshots();b['threads']=[dict(tid='7',read_error='denied')]
        v=interval(a,b,100,5,25);self.assertEqual([],v['exited']);self.assertEqual([7],v['unreadable_after'])
        v=interval(b,dict(a,scan_start_ns=40,scan_end_ns=45),100,35,39);self.assertEqual([],v['born']);self.assertEqual([7],v['unreadable_before'])


if __name__=='__main__':unittest.main()
