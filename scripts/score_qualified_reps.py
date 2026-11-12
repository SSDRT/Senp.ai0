"""Score per-attempt decisions only against independently reviewed whole-clip annotations.

Uses ordered one-to-one interval matching. Unsupported/unassessed predictions
remain in recall denominators; unmatched accepted events are false positives.
"""
import argparse
import json
from pathlib import Path

GOOD={'CORRECT','ACCEPTABLE'}
LABELS=GOOD|{'INCORRECT','UNASSESSABLE'}
VERDICTS=GOOD|{'REJECTED','UNASSESSABLE'}

def key(row):return tuple(row[k] for k in ['video_sha256','reference_video_sha256','exercise','side'])
def overlap(a,b):
    intersection=max(0,min(a['completed_ms'],b['end_ms'])-max(a['started_ms'],b['start_ms']))
    union=max(a['completed_ms'],b['end_ms'])-min(a['started_ms'],b['start_ms'])
    return intersection/union if union else 0.0

def matching(predicted,truth):
    n,m=len(predicted),len(truth);dp=[[0.0]*(m+1) for _ in range(n+1)]
    for i in range(1,n+1):
        for j in range(1,m+1):
            weight=overlap(predicted[i-1],truth[j-1])
            dp[i][j]=max(dp[i-1][j],dp[i][j-1],dp[i-1][j-1]+weight if weight>=0.3 else -1)
    result=[];i,j=n,m
    while i and j:
        weight=overlap(predicted[i-1],truth[j-1])
        if weight>=0.3 and abs(dp[i][j]-dp[i-1][j-1]-weight)<1e-10:
            result.append((i-1,j-1));i-=1;j-=1
        elif dp[i-1][j]>=dp[i][j-1]:i-=1
        else:j-=1
    return result

def score(report,annotations):
    if annotations.get('schema')!='rep-acceptance-annotations/1' or annotations.get('complete_review') is not True:
        raise ValueError('Reviewed rep/fault annotations required; whole-clip labels and templates cannot be scored')
    predictions={key(row):row for row in report['rows']}
    if len(predictions)!=len(report['rows']):raise ValueError('Duplicate prediction task')
    tasks=annotations.get('tasks',[])
    if not tasks or len({key(t) for t in tasks})!=len(tasks):raise ValueError('Missing or duplicate annotation task')
    totals=dict(accepted=0,true_accepted=0,good=0,bad=0,unknown=0,bad_accepted=0,bad_rejected=0,
                good_rejected=0,unknown_accepted=0,assessable_decisions=0,unmatched_accepted=0)
    confusion={}
    for task in tasks:
        if task.get('reviewed_entire_clip') is not True or task.get('expert_reviewed') is not True:
            raise ValueError('Each task needs full-clip independent review and expert adjudication')
        reviewers=task.get('reviewers',[])
        if len(set(reviewers))<2 or any(not r.strip() for r in reviewers):raise ValueError('At least two distinct reviewers required')
        if task.get('split')=='test' and task.get('previously_seen') is not False:raise ValueError('Previously seen participants cannot be a fresh test set')
        if key(task) not in predictions:raise ValueError('Prediction provenance does not match annotations')
        row=predictions[key(task)]
        bounds=[task.get('evaluated_start_ms'),task.get('evaluated_end_ms')]
        if any(type(v) is not int for v in bounds) or not 0<=bounds[0]<bounds[1]:
            raise ValueError('Explicit evaluated source interval required')
        if bounds!=[row.get('evaluated_start_ms'),row.get('evaluated_end_ms')]:
            raise ValueError('Reviewed interval does not match prediction interval')
        truth=sorted(task.get('attempts',[]),key=lambda a:a['start_ms'])
        for index,t in enumerate(truth):
            if t.get('label') not in LABELS or not 0<=t['start_ms']<t['end_ms']:raise ValueError('Invalid ground truth attempt')
            if t['start_ms']<task.get('evaluated_start_ms',0) or t['end_ms']>task.get('evaluated_end_ms',float('inf')):raise ValueError('Annotation outside evaluated source interval')
            if index and t['start_ms']<truth[index-1]['end_ms']:raise ValueError('Overlapping ground truth attempts')
        predicted=sorted(row.get('attempts',[]) if row.get('status')=='REPLAYED' else [],key=lambda a:a['started_ms'])
        for p in predicted:
            if p['verdict'] not in VERDICTS or not 0<=p['started_ms']<p['completed_ms']:raise ValueError('Invalid predicted attempt')
        pairs=matching(predicted,truth);by_truth={j:i for i,j in pairs};matched_pred={i for i,_ in pairs}
        totals['accepted']+=sum(p['verdict'] in GOOD for p in predicted)
        totals['unmatched_accepted']+=sum(p['verdict'] in GOOD for i,p in enumerate(predicted) if i not in matched_pred)
        for j,t in enumerate(truth):
            outcome=predicted[by_truth[j]]['verdict'] if j in by_truth else 'NO_DECISION'
            confusion[(t['label'],outcome)]=confusion.get((t['label'],outcome),0)+1
            if t['label'] in GOOD:
                totals['good']+=1;totals['true_accepted']+=outcome in GOOD;totals['good_rejected']+=outcome=='REJECTED'
            elif t['label']=='INCORRECT':
                totals['bad']+=1;totals['bad_accepted']+=outcome in GOOD;totals['bad_rejected']+=outcome=='REJECTED'
            else:
                totals['unknown']+=1;totals['unknown_accepted']+=outcome in GOOD
            totals['assessable_decisions']+=t['label']!='UNASSESSABLE' and outcome in GOOD|{'REJECTED'}
    ratio=lambda a,b:totals[a]/totals[b] if totals[b] else None
    return {'kind':'reviewed-attempt-interval-matching','release_qualified':False,'reviewed_tasks':len(tasks),'prediction_tasks':len(predictions),
            'counts':totals,'qualified_precision':ratio('true_accepted','accepted'),'good_acceptance':ratio('true_accepted','good'),
            'bad_acceptance':ratio('bad_accepted','bad'),'bad_rejection':ratio('bad_rejected','bad'),
            'good_rejection':ratio('good_rejected','good'),'unknown_acceptance':ratio('unknown_accepted','unknown'),
            'decision_coverage':totals['assessable_decisions']/(totals['good']+totals['bad']) if totals['good']+totals['bad'] else None,
            'confusion':[{'actual':a,'predicted':p,'count':n} for (a,p),n in sorted(confusion.items())],
            'limits':['Metrics cover reviewed tasks only, including missed attempts.','Participant-clustered uncertainty, onset latency and device qualification still required for release.']}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--predictions',type=Path,required=True)
    parser.add_argument('--annotations',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    result=score(json.loads(args.predictions.read_text()),json.loads(args.annotations.read_text()))
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ['counts','confusion','limits']},indent=2))
