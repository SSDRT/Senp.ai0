import copy
import math
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fetch_mmfit_reference_video import verify_metadata, VIDEO, SIZE, MD5
from extract_mmfit_reference import activity_proposal, propose_bouts, validate_protocol, PARAMETERS, SOURCE_SHA


def seated_raise():
    # Constructed geometry for mechanism checks only, never measured form accuracy.
    raw = [[.5,.5,0.,.95,.95,0.,0.,0.] for _ in range(33)]
    for shoulder,elbow,wrist,hip,knee,ankle,sign in [(11,13,15,23,25,27,-1),(12,14,16,24,26,28,1)]:
        for i, xyz in [(shoulder,[sign*.2,1.,0.]),(elbow,[sign*.6,1.,0.]),(wrist,[sign*1.,1.,0.]),
                       (hip,[sign*.2,0.,0.]),(knee,[sign*.2,0.,.5]),(ankle,[sign*.2,-.5,.5])]: raw[i][5:8]=xyz
    return raw


class MmfitReferenceTests(unittest.TestCase):
    def test_license_and_exact_publisher_binary_required(self):
        good = dict(id=7672767,metadata=dict(license=dict(id='cc-by-4.0'),access_right='open'),
                    files=[dict(key=VIDEO,size=SIZE,checksum='md5:'+MD5)])
        verify_metadata(good)
        for kind in ['license','access','record','size','checksum','missing']:
            changed=copy.deepcopy(good)
            if kind=='license':changed['metadata']['license']['id']='cc-by-nc-4.0'
            elif kind=='access':changed['metadata']['access_right']='restricted'
            elif kind=='record':changed['id']=1
            elif kind=='size':changed['files'][0]['size']-=1
            elif kind=='checksum':changed['files'][0]['checksum']='md5:'+'0'*32
            else:changed['files']=[]
            with self.assertRaises(ValueError):verify_metadata(changed)

    def test_scope_blocks_annotations_final_subjects_parameter_changes_and_wrong_video(self):
        good=dict(schema='senpai-mmfit-reference/1',parameters=PARAMETERS,video=VIDEO,video_sha256=SOURCE_SHA,
            license='CC BY 4.0',reference_only=True,allow_final_test_access=False,annotation_archive_access=False,
            selection='first-chronological-eligible-bout',exercise='lateral_raise',variant='seated-dumbbell-lateral-raise',source_receipts=[])
        validate_protocol(good)
        for key,value in [('reference_only',False),('allow_final_test_access',True),('annotation_archive_access',True),
                          ('selection','best-calibration-bout'),('video','w00_rgb.mp4'),('exercise','glute_bridge'),
                          ('parameters',dict(PARAMETERS,minimum_confidence=.2))]:
            with self.assertRaises(ValueError):validate_protocol(dict(good,**{key:value}))

    def test_seated_lateral_geometry_is_rigid_transform_invariant_and_unchanged(self):
        points=seated_raise();before=copy.deepcopy(points)
        self.assertTrue(activity_proposal(points));self.assertEqual(points,before)
        for p in points:
            x,y,z=p[5:8];p[5:8]=[.8*x+.6*z+4,y-2,-.6*x+.8*z+10]
        self.assertTrue(activity_proposal(points))

    def test_missing_low_confidence_outside_and_nonfinite_never_propose_activity(self):
        for kind in ['missing','visibility','presence','outside','nan','degenerate']:
            points=seated_raise()
            if kind=='missing':points=points[:32]
            elif kind=='visibility':points[27][3]=.64
            elif kind=='presence':points[15][4]=.64
            elif kind=='outside':points[12][0]=1.2
            elif kind=='nan':points[25][6]=math.nan
            else:points[12][5:8]=points[11][5:8]
            self.assertFalse(activity_proposal(points),kind)

    def test_standing_bent_arm_and_forward_raise_cannot_impersonate_target_activity(self):
        for kind in ['standing','bent-arm','forward']:
            points=seated_raise()
            for shoulder,elbow,wrist,hip,knee,ankle,sign in [(11,13,15,23,25,27,-1),(12,14,16,24,26,28,1)]:
                if kind=='standing':
                    points[knee][5:8]=[sign*.2,-.5,0.];points[ankle][5:8]=[sign*.2,-1.,0.]
                elif kind=='bent-arm':points[wrist][5:8]=[sign*.6,1.4,0.]
                else:
                    points[elbow][5:8]=[sign*.2,1.,.4];points[wrist][5:8]=[sign*.2,1.,.8]
            self.assertFalse(activity_proposal(points),kind)

    def test_first_eligible_contiguous_bout_selection_preserves_original_times(self):
        times=[1000,1500,2000]+list(range(10000,20001,1000))+list(range(30000,40001,1000))
        rows=[dict(timestamp_ms=t,landmarks=seated_raise()) for t in times]
        selected=propose_bouts(rows,50000)
        self.assertEqual(len(selected),2)
        self.assertEqual(selected[0],dict(first_proposal_ms=10000,last_proposal_ms=20000,proposal_observations=11,start_ms=8000,end_ms=22000))
        self.assertEqual(selected[1]['first_proposal_ms'],30000)
        self.assertEqual(propose_bouts(rows[:3],50000),[])
        with self.assertRaises(ValueError):propose_bouts(rows+rows[:1],50000)


if __name__=='__main__':unittest.main()
