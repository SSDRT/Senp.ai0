import unittest
from audit_independent_rgb_sources import rights_status,label_scope


class IndependentRgbSourceTests(unittest.TestCase):
    def test_annotation_apache_badge_does_not_license_videos(self):
        self.assertEqual('ANNOTATIONS_ONLY_VERIFY_EACH_ORIGINAL_MEDIA_GRANT',rights_status('license: apache-2.0\nNo source videos are included or relicensed.'))

    def test_specific_restriction_takes_priority_over_open_annotation_grant(self):
        self.assertEqual('RESTRICTED_NOT_OPEN_APPLICATION_DATA',rights_status('Annotations licensed Apache 2.0; media for non-commercial purposes only.'))

    def test_code_license_does_not_satisfy_separate_data_agreement(self):
        self.assertEqual('SEPARATE_DATA_AGREEMENT_UNRESOLVED',rights_status('Code Apache 2.0. Please sign the License Agreement to download data.'))
        self.assertEqual('NO_VERIFIED_MEDIA_GRANT',rights_status('Code MIT; dataset publicly available.'))

    def test_hidden_scripts_cannot_supply_rights(self):
        self.assertEqual('NO_VERIFIED_MEDIA_GRANT',rights_status('<script>Videos are not redistributed</script><p>Code Apache</p>'))

    def test_correct_answer_on_one_aspect_does_not_label_a_whole_rep(self):
        self.assertEqual('ASPECT_QUESTION_NOT_COMPLETE_REP_CORRECTNESS',label_scope(['question_id','gold','correct_form_option','video_sha256'],'judgement'))

    def test_error_interval_complement_does_not_create_correct_rep_labels(self):
        self.assertEqual('ERROR_INTERVALS_NOT_EXHAUSTIVE_REP_CORRECTNESS',label_scope(['gold','form_error','error_detail','video_sha256'],'temporal_grounding'))

    def test_video_hash_cannot_replace_repetition_and_subject_identity(self):
        fields=['rep_id','start_ms','end_ms','correct','video_sha256']
        self.assertEqual('MISSING_COMPLETE_REP_OR_SUBJECT_IDENTITY',label_scope(fields,'complete_rep_correctness'))
        self.assertEqual('DECLARED_REP_SCHEMA_REQUIRES_SOURCE_SPLIT_AND_LABEL_AUDIT',label_scope(fields+['subject_id'],'complete_rep_correctness'))


if __name__=='__main__':unittest.main()
