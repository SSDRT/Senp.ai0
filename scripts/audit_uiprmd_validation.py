"""Explain missed reps without changing labels, sides, ranges or verdicts."""
import json
from collections import Counter
import numpy as np
from fetch_uiprmd import DATA, checksum
from evaluate_uiprmd_development import evaluate_rows, match_events, QUALIFIED


def main():
    replay_path = DATA/'validation-replay.json'
    replay = json.loads(replay_path.read_text())
    quality = json.loads((DATA/'quality-head-validation.json').read_text())
    if checksum(replay_path,'sha256') != quality['replay_sha256']:raise ValueError('Replay changed')
    rows = []
    for index,row in enumerate(replay['rows']):
        filename = f'{row["movement"]}-s{row["subject"]}-'+('correct' if row['correct'] else 'incorrect')+'.json'
        record = json.loads((DATA/'validation-vicon'/filename).read_text())
        ranges = {}
        for side in ['left','right']:
            key = 'angle.'+side+'_shoulder'
            observed = [frame['values'][key] for frame in record['frames'] if key in frame['values']]
            ranges[side] = dict(zip(['q05','q50','q95'],map(float,np.quantile(observed,[.05,.5,.95]))))
            ranges[side]['q95_minus_q05'] = ranges[side]['q95']-ranges[side]['q05']
        matches,_ = match_events(row['episodes'],row['attempts'])
        vetoed = {score['attempt'] for score in quality['scores'] if score['row'] == index and
                  score['bad_score_uncalibrated'] >= .75}
        qualified = [match['attempt_index'] for match in matches.values() if
                     row['attempts'][match['attempt_index']]['verdict'] in QUALIFIED]
        metrics = evaluate_rows([row]);metrics.pop('rep_results')
        rows.append({'subject':row['subject'],'movement':row['movement'],'correct':row['correct'],
                     'engine_metrics':metrics,'completed_attempts':len(row['attempts']),
                     'attempt_reasons':dict(Counter(reason for attempt in row['attempts'] for reason in attempt['reasons'])),
                     'qualified_counts_vetoed':len(set(qualified)&vetoed),'bilateral_shoulder_proxy_quantiles_degrees':ranges})
    result = {'schema':'uiprmd-validation-error-audit/1','kind':'diagnostic-only-no-candidate-or-label-change',
              'replay_sha256':checksum(replay_path,'sha256'),'quality_result_sha256':checksum(DATA/'quality-head-validation.json','sha256'),
              'subjects_opened':['05','06','07'],'final_test_subjects_opened':False,'rows':rows,
              'limitations':['Reason counts may overlap within an attempt.',
                  'Bilateral angle ranges are descriptive only; they do not prove anatomical correctness or authorize changing the frozen RIGHT-side protocol.',
                  'No incorrect/correct source label is dropped or rewritten.']}
    target = DATA/'quality-head-validation-error-audit.json'
    target.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(json.dumps(rows,indent=2))


if __name__ == '__main__':main()
