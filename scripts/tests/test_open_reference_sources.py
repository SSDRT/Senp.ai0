import copy
import importlib.util
from pathlib import Path
import unittest

SPEC=importlib.util.spec_from_file_location('sources',Path(__file__).resolve().parents[1]/'audit_open_reference_sources.py')
sources=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(sources)


class OpenReferenceSourceTests(unittest.TestCase):
    def setUp(self):
        self.video=dict(id=1,exercise=265,license=2,license_author='Original contributor',
            video='https://wger.de/media/exercise-video/265/demo.mp4',duration='12.5')
        self.licenses={2:dict(id=2,url='https://creativecommons.org/licenses/by-sa/4.0/deed.en')}
        self.exercises={265:dict(id=265)}

    def status(self):
        return sources.licensed_video_candidate(self.video,self.licenses,self.exercises)

    def test_asset_specific_copyright_warning_overrides_public_domain_banner(self):
        self.assertEqual('REJECT_ASSET_SPECIFIC_COPYRIGHT_RESTRICTION',sources.asset_rights(
            '<h4>PUBLIC DOMAIN</h4><h4>Copyright Information</h4><p>Asset contains copyrighted material</p>'))
        self.assertEqual('REJECT_ASSET_SPECIFIC_COPYRIGHT_RESTRICTION',sources.asset_rights(
            '<p>Portions of the asset are restricted and are not licensed for distribution.</p>'))

    def test_scripts_do_not_supply_or_remove_a_visible_asset_license(self):
        self.assertEqual('NO_VERIFIED_ASSET_LICENSE',sources.asset_rights('<script>PUBLIC DOMAIN</script><p>No grant</p>'))
        self.assertEqual('PUBLIC_DOMAIN_BANNER_REQUIRES_ASSET_AND_SCOPE_VERIFICATION',sources.asset_rights(
            '<h4>Public Domain</h4><script>Asset contains copyrighted material</script>'))

    def test_open_asset_is_only_a_candidate_never_camera_or_rep_truth(self):
        self.assertEqual('LICENSED_CANDIDATE_REQUIRES_ORIGINAL_VIDEO_QA_AND_CALIBRATION',self.status())
        self.licenses[2]['url']='http://creativecommons.org/publicdomain/zero/1.0/'
        self.assertEqual('LICENSED_CANDIDATE_REQUIRES_ORIGINAL_VIDEO_QA_AND_CALIBRATION',self.status())

    def test_no_global_or_noncommercial_license_can_replace_the_asset_grant(self):
        self.video.pop('license')
        self.assertEqual('MISSING_ASSET_LICENSE',self.status())
        self.video['license']=2
        for url in ['https://creativecommons.org/licenses/by-nc/4.0/',
                    'https://creativecommons.org/licenses/by-nd/4.0/',
                    'https://creativecommons.org.other.invalid/licenses/by/4.0/']:
            self.licenses[2]['url']=url
            self.assertEqual('ASSET_LICENSE_NOT_IN_OPEN_ALLOWLIST',self.status())

    def test_hip_thrust_or_leg_extension_is_not_relabelled_as_floor_glute_bridge(self):
        for exercise in [294,1906,True]:
            self.video['exercise']=exercise
            self.assertEqual('OUTSIDE_DECLARED_VARIANT',self.status())

    def test_attribution_and_verified_media_identity_are_required(self):
        self.video['license_author']=' '
        self.assertEqual('MISSING_ASSET_ATTRIBUTION',self.status())
        self.video['license_author']='Author';self.video['video']='https://another.invalid/video.mp4'
        self.assertEqual('UNVERIFIED_PUBLISHER_MEDIA_URL',self.status())
        self.video['video']='https://wger.de/media/exercise-video/265/demo.mp4'
        self.video['duration']='NaN'
        self.assertEqual('INVALID_PUBLISHER_DURATION',self.status())

    def test_an_incomplete_catalogue_cannot_prove_no_eligible_video_exists(self):
        videos=dict(count=1,next=None,results=[self.video])
        licenses=dict(count=1,next=None,results=list(self.licenses.values()))
        exercises=dict(count=1,next=None,results=list(self.exercises.values()))
        self.assertEqual(1,len(sources.audit_catalogue(videos,licenses,exercises)['eligible_candidates']))
        partial=copy.deepcopy(videos);partial['next']='https://wger.de/api/v2/video/?offset=1'
        with self.assertRaises(ValueError):sources.audit_catalogue(partial,licenses,exercises)
        partial=copy.deepcopy(videos);partial['count']=2
        with self.assertRaises(ValueError):sources.audit_catalogue(partial,licenses,exercises)
        duplicate=copy.deepcopy(videos);duplicate['results']*=2;duplicate['count']=2
        with self.assertRaises(ValueError):sources.audit_catalogue(duplicate,licenses,exercises)


if __name__=='__main__':unittest.main()
