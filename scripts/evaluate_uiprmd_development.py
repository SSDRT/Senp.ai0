"""One-to-one continuous-event evaluation against original development rep labels.

No labels enter the live engine. Missing, duplicate and unassessable events remain
in denominators. Development metrics cannot qualify the held-out readiness gate.
"""
import json
from collections import Counter
from fetch_uiprmd import DATA, ROOT, checksum

QUALIFIED = {'CORRECT', 'ACCEPTABLE'}
MINIMUM_TEMPORAL_IOU = .25


def match_events(episodes, attempts):
    """Label-blind greedy maximum temporal IoU, one event per original repetition.

    Segment timestamps delimit the complete original motion. Attempt starts are
    causal departure times, so IoU permits different dwell boundaries. No nearest
    out-of-segment event or time stretching is used. Ties use original row order.
    """
    candidates = []
    for i, episode in enumerate(episodes):
        start, end = episode['start_ms'], episode['end_ms']+10
        if end <= start:
            raise ValueError('Invalid repetition bounds')
        for j, attempt in enumerate(attempts):
            a, b = attempt['started_ms'], attempt['completed_ms']
            if b <= a:
                raise ValueError('Invalid attempt bounds')
            overlap = max(0,min(end,b)-max(start,a))
            union = max(end,b)-min(start,a)
            iou = overlap/union
            if iou >= MINIMUM_TEMPORAL_IOU:
                candidates.append((-iou,i,j))
    matched,used = {},set()
    for negative_iou,i,j in sorted(candidates):
        if i not in matched and j not in used:
            matched[i] = {'attempt_index':j,'temporal_iou':-negative_iou};used.add(j)
    return matched,[j for j in range(len(attempts)) if j not in used]


def evaluate_rows(rows):
    counts = Counter(); details = []
    for row in rows:
        episodes,attempts = row['episodes'],row['attempts']
        matched,unmatched = match_events(episodes,attempts)
        for i,episode in enumerate(episodes):
            match = matched.get(i)
            attempt = attempts[match['attempt_index']] if match else None
            verdict = attempt['verdict'] if attempt else 'NO_EVENT'
            correct = episode['correct']
            if type(correct) is not bool:
                raise ValueError('Original boolean correctness label required')
            counts['correct_reps' if correct else 'incorrect_reps'] += 1
            counts['total_reps'] += 1
            if verdict in QUALIFIED:
                counts['true_positive' if correct else 'false_positive'] += 1
            elif correct:
                counts['false_negative'] += 1
            if verdict in QUALIFIED | {'REJECTED'}:
                counts['assessable_reps'] += 1
            counts['verdict_'+verdict] += 1
            details.append({'subject':row['subject'],'movement':row['movement'],'correct':correct,
                            'episode':episode['episode'],'verdict':verdict,'temporal_match':match})
        for j in unmatched:
            counts['unmatched_events'] += 1
            if attempts[j]['verdict'] in QUALIFIED:
                counts['false_positive'] += 1;counts['unmatched_qualified_events'] += 1
    tp,fp=counts['true_positive'],counts['false_positive']
    return {'counts':dict(counts),'precision':tp/(tp+fp) if tp+fp else None,
            'good_rep_recall':tp/counts['correct_reps'] if counts['correct_reps'] else None,
            'assessable_coverage':counts['assessable_reps']/counts['total_reps'] if counts['total_reps'] else None,
            'rep_results':details}


def main():
    replay_path=DATA/'development-replay.json'
    replay=json.loads(replay_path.read_text())
    if replay['schema']!='uiprmd-development-replay/1':raise ValueError('Unexpected replay schema')
    if {r['subject'] for r in replay['rows']}!={'01','02','03','04'}:raise ValueError('Development split changed')
    result=evaluate_rows(replay['rows'])
    result.update(schema='uiprmd-development-metrics/1',kind='development-diagnostic-not-held-out-accuracy',
                  measurement_domain='skeletal',existing_independent_labels=True,frozen_before_test=False,
                  readiness_points_earned=0,raw_landmark_network_trained=False,
                  minimum_temporal_iou=MINIMUM_TEMPORAL_IOU,replay_sha256=checksum(replay_path,'sha256'),
                  subjects=json.loads((ROOT/'models/uiprmd-evaluation-protocol.json').read_text())['subjects'],
                  note='Whole-rep labels do not locate fault onset. Marker proxies are not anatomical joint centres or RGB pose truth.')
    result['per_movement']={movement:{k:v for k,v in evaluate_rows([r for r in replay['rows'] if r['movement']==movement]).items() if k!='rep_results'}
                            for movement in ['m01','m07']}
    (DATA/'development-metrics.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(json.dumps({k:result[k] for k in ['counts','precision','good_rep_recall','assessable_coverage','per_movement']},indent=2))


if __name__=='__main__':main()
