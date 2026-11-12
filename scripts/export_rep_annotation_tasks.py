"""Prepare empty independent-review tasks. Engine proposals are kept in a separate file."""
import json
from pathlib import Path
from extract_exercise_quality_features import DATA

def main():
    report=json.loads((DATA/'qualified-video-replay/summary.json').read_text())
    tasks=[]
    for row in report['rows']:
        tasks.append({key:row[key] for key in ['video','video_sha256','reference_video','reference_video_sha256','exercise','side','view','evaluated_start_ms','evaluated_end_ms']}|
                     {'split':'development','previously_seen':True,'reviewed_entire_clip':False,'expert_reviewed':False,'reviewers':[],
                      'attempts':[],'instructions':'Review the entire evaluated source interval and reference video; label all attempts, including ones absent from model proposals. Labels: CORRECT, ACCEPTABLE, INCORRECT, UNASSESSABLE. Use source PTS and the selected visible side. Attempts cut off by the evaluated interval are unassessable. Whole-clip labels are not rep truth.'})
    target=DATA/'rep-annotations.template.json'
    target.write_text(json.dumps({'schema':'rep-acceptance-annotations/1','complete_review':False,'dataset_doi':'10.17632/kgbb3yn47p.3','tasks':tasks},indent=2)+'\n')
    print('Unlabelled independent-review tasks',len(tasks),'; no accuracy score inferred')

if __name__=='__main__':main()
