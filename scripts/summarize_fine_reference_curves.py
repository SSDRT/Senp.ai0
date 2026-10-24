"""Reference-only finer-curve recovery, preserving all established selections."""
import json
from collections import defaultdict
from fetch_uiprmd import ROOT, checksum
from summarize_autonomous_pose_experiments import reference_key, source_key, summarize

PRIORITY = [('full','standard',6),('heavy','standard',6),('full','robust',6),('heavy','robust',6),
            ('full','standard',10),('heavy','standard',10),('full','robust',10),('heavy','robust',10)]
RECOVERABLE = {'WEAK_REFERENCE','LOW_SELF_RECONSTRUCTION'}
DATA = ROOT/'test-artifacts/exercise-knowledge'


def choose(groups, include_fine=True):
    by_reference = {}
    source_keys = None
    for candidate in PRIORITY:
        rows = groups[candidate]
        keys = [source_key(row) for row in rows]
        if len(keys)!=len(set(keys)):raise ValueError('Duplicate source runs')
        if source_keys is not None and set(keys)!=source_keys:raise ValueError('Candidate source cohorts differ')
        source_keys = set(keys)
        refs = defaultdict(list)
        for row in rows:refs[reference_key(row)].append(row)
        for key,values in refs.items():
            if len({r['status'] for r in values})!=1:raise ValueError('Source outcomes must not change reference availability')
            by_reference[candidate,key] = values[0]['status']
    references = {reference_key(row) for row in groups[PRIORITY[0]]}
    selected = {}
    for key in references:
        selected[key] = None
        for candidate in PRIORITY if include_fine else PRIORITY[:4]:
            model,fit,nodes = candidate
            if (fit=='robust' or nodes==10) and by_reference[(model,'standard',6),key] not in RECOVERABLE:
                continue
            if by_reference[candidate,key]=='REPLAYED':selected[key]=candidate;break
    return selected


def main():
    protocol_path = ROOT/'models/fine-reference-curve-experiment.json'
    protocol = json.loads(protocol_path.read_text())
    if protocol['selection_priority'] != [list(c) for c in PRIORITY]:raise ValueError('Frozen priority changed')
    groups,inputs = {},[]
    for model,fit,nodes in PRIORITY:
        name = f'mirror-calibration-complete-{model}-{fit}' if nodes==6 else f'fine-curve-complete-{model}-{fit}'
        path = DATA/name/'summary.json'
        groups[model,fit,nodes] = json.loads(path.read_text())['rows']
        inputs.append({'path':path.relative_to(ROOT).as_posix(),'sha256':checksum(path,'sha256')})
    before = choose(groups,False);after = choose(groups)
    if len(before)!=32 or len(groups[PRIORITY[0]])!=96:raise ValueError('Frozen denominators changed')
    index = {c:{source_key(r):r for r in rows} for c,rows in groups.items()}
    def rows(choices):
        return [r if choices[reference_key(r)] is None else index[choices[reference_key(r)]][source_key(r)] for r in groups[PRIORITY[0]]]
    old,new = rows(before),rows(after)
    previous = {source_key(r):r for r in old}
    preserved = []
    for row in new:
        prior = previous[source_key(row)]
        if before[reference_key(row)] is not None:
            if before[reference_key(row)]!=after[reference_key(row)]:raise ValueError('Established selection replaced')
            for field in ['status','reference_key','counts','attempts','faults']:
                if row.get(field)!=prior.get(field):raise ValueError('Established output changed: '+field)
            preserved.append(source_key(row))
    gains = [dict(zip(['reference_video_sha256','exercise','side','view'],key),candidate=after[key])
             for key in sorted(after) if before[key] is None and after[key] is not None]
    result = {'schema':'senpai-fine-reference-curve-research/1','kind':'reference-only-recovery-not-human-rep-accuracy',
              'protocol_sha256':checksum(protocol_path,'sha256'),'before':summarize(old),'after':summarize(new),
              'gained_reference_sides':gains,'lost_reference_sides':[],'preserved_previously_ready_runs':len(preserved),
              'production_promoted':False,'live_human_rep_precision':None,'final_test_trajectories_opened':False,
              'inputs':inputs,'selected_rows':new}
    destination = DATA/'fine-reference-curve-summary';destination.mkdir(exist_ok=True)
    (destination/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ['selected_rows','inputs']},indent=2))


if __name__=='__main__':main()
