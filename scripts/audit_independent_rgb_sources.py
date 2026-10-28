"""Offline research intake: open annotations do not grant third-party video rights.

This checks declared label scope, not prediction accuracy. It never converts a
question, clip label, or missing error interval into a complete-rep form label.
"""
import argparse
import hashlib
import json
from pathlib import Path
from audit_open_reference_sources import VisibleText

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge/independent-rgb-source-review-20261028'


def rights_status(text):
    parser=VisibleText();parser.feed(text)
    visible=' '.join(' '.join(parser.parts).casefold().split())
    if any(x in visible for x in ['non-commercial purposes','non-commercial academic','research purposes only','solely for research use']):
        return 'RESTRICTED_NOT_OPEN_APPLICATION_DATA'
    if 'videos are not redistributed' in visible or 'no source videos are included or relicensed' in visible:
        return 'ANNOTATIONS_ONLY_VERIFY_EACH_ORIGINAL_MEDIA_GRANT'
    if 'sign the' in visible and 'license agreement' in visible:
        return 'SEPARATE_DATA_AGREEMENT_UNRESOLVED'
    return 'NO_VERIFIED_MEDIA_GRANT'


def label_scope(fields,task):
    fields=set(fields)
    if task in {'perception','judgement','multiple_choice'} and {'question_id','gold','correct_form_option'}<=fields:
        return 'ASPECT_QUESTION_NOT_COMPLETE_REP_CORRECTNESS'
    if task=='temporal_grounding' and {'gold','form_error','error_detail'}<=fields:
        return 'ERROR_INTERVALS_NOT_EXHAUSTIVE_REP_CORRECTNESS'
    if task=='complete_rep_correctness' and {'rep_id','start_ms','end_ms','correct','subject_id','video_sha256'}<=fields:
        return 'DECLARED_REP_SCHEMA_REQUIRES_SOURCE_SPLIT_AND_LABEL_AUDIT'
    return 'MISSING_COMPLETE_REP_OR_SUBJECT_IDENTITY'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',type=Path,default=DATA);args=parser.parse_args()
    out=args.directory/'independent-source-eligibility.json'
    if out.exists():raise FileExistsError('Preserve source review')
    manifest=json.loads((args.directory/'metadata-receipts.json').read_bytes())
    if manifest['raw_videos_downloaded'] or manifest['parquet_labels_opened']:raise PermissionError('Metadata-only intake required')
    for row in manifest['receipts']:
        if row['path'] and hashlib.sha256((ROOT/row['path']).read_bytes()).hexdigest()!=row['sha256']:
            raise ValueError('Publisher metadata changed')
    rows=[]
    for name in ['Fitness-AQA','MyoMechanix','EgoExo-Fitness','FitAQA']:
        path=args.directory/(name+'-README.md')
        rows.append(dict(source=name,rights_status=rights_status(path.read_text(encoding='utf-8')),metadata_sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    fields=['question_id','task','dataset','video_name','video_sha256','action_name','form_error','question','options','gold','correct_form_option']
    report=dict(schema='independent-rgb-source-intake/1',sources=rows,
        FitAQA_multiple_choice_scope=label_scope(fields,'multiple_choice'),
        FitAQA_grounding_scope=label_scope(['question_id','task','dataset','video_name','video_sha256','action_name','form_error','error_detail','gold'],'temporal_grounding'),
        source_repetition_count=None,subject_disjoint_test_roster=None,media_license_from_annotation_license=False,
        correct_reps_inferred_from_error_absence=False,per_rep_form_labels_inferred_from_aspect_answers=False,
        eligible_independent_evaluations=0,raw_video_downloaded=False,benchmark_parquet_labels_opened=False,
        final_uiprmd_subjects_opened=False,new_human_labels=0,production_changed=False,readiness_points_earned=0,human_live_rep_precision=None)
    out.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
