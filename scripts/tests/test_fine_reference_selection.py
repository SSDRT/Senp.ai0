import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from summarize_fine_reference_curves import choose,PRIORITY


def groups():
    return {candidate:[dict(video_sha256='source',reference_video_sha256='reference',exercise='biceps_curl',side='LEFT',
                            view='side',status='LOW_SELF_RECONSTRUCTION',counts={'qualified':0})] for candidate in PRIORITY}


class FineSelectionTests(unittest.TestCase):
    def test_legacy_fit_wins_even_if_later_source_counts_are_higher(self):
        rows=groups();rows['heavy','robust',6][0]['status']='REPLAYED'
        rows['full','standard',10][0].update(status='REPLAYED',counts={'qualified':999})
        self.assertEqual(('heavy','robust',6),next(iter(choose(rows).values())))

    def test_only_reference_availability_selects_the_frozen_recovery_priority(self):
        rows=groups();rows['heavy','standard',10][0]['status']='REPLAYED'
        rows['full','robust',10][0].update(status='REPLAYED',counts={'qualified':999})
        self.assertEqual(('heavy','standard',10),next(iter(choose(rows).values())))
        self.assertIsNone(next(iter(choose(rows,False).values())))

    def test_missing_evidence_is_not_recovered_by_changing_curve_fit(self):
        rows=groups()
        for model in ['full','heavy']:rows[model,'standard',6][0]['status']='MISSING_FEATURE'
        for candidate in PRIORITY[2:]:rows[candidate][0]['status']='REPLAYED'
        self.assertIsNone(next(iter(choose(rows).values())))

    def test_duplicate_or_inconsistent_reference_rows_cannot_choose_by_source(self):
        rows=groups();rows[PRIORITY[-1]].append(dict(rows[PRIORITY[-1]][0]))
        with self.assertRaises(ValueError):choose(rows)
        rows=groups()
        for candidate in PRIORITY:
            rows[candidate].append(dict(rows[candidate][0],video_sha256='other'))
        rows[PRIORITY[-1]][1]['status']='REPLAYED'
        with self.assertRaises(ValueError):choose(rows)


if __name__=='__main__':unittest.main()
