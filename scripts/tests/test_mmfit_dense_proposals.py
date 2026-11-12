from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from refine_mmfit_activity_proposals import seed_windows


class DenseProposalTests(unittest.TestCase):
    def test_all_original_seeds_receive_fixed_context_and_overlaps_merge_without_retiming(self):
        self.assertEqual(seed_windows([1000,5000,40000],50000),[
            dict(start_ms=0,end_ms=15000,seed_observations=2),dict(start_ms=30000,end_ms=50000,seed_observations=1)])
        self.assertEqual(seed_windows([],50000),[])

    def test_noncausal_duplicate_or_out_of_recording_seeds_are_rejected(self):
        for hits in [[1000,1000],[2000,1000],[-1],[50001]]:
            with self.assertRaises(ValueError):seed_windows(hits,50000)


if __name__=='__main__':unittest.main()
