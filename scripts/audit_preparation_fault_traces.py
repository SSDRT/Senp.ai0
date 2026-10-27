"""Compare all replay traces; never treat weak clip labels as per-rep truth."""
import json,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];DATA=ROOT/'test-artifacts/exercise-knowledge'


def semantic(v):
    if isinstance(v,dict):return {k:semantic(x) for k,x in v.items() if k not in {'compiler','reference_key'}}
    if isinstance(v,list):return [semantic(x) for x in v]
    return v


def main():
    rows=[]
    for cohort in ['capped','complete']:
        for model in ['full','heavy']:
            for fit in ['standard','robust']:
                tag=f'{cohort}-{model}-{fit}';old=DATA/('clear-ready-'+tag);new=DATA/('preparation-fault-'+tag)
                if {p.name for p in old.glob('*.json')}!={p.name for p in new.glob('*.json')}:raise ValueError('File cohort changed')
                changes=[];traces=0
                for path in sorted(new.glob('*.json')):
                    if path.name=='summary.json':continue
                    a=json.loads((old/path.name).read_bytes());b=json.loads(path.read_bytes());traces+=1
                    aa=a['trace'];bb=b['trace']
                    if len(aa)!=len(bb):raise ValueError('Original frame count changed')
                    for x,y in zip(aa,bb):
                        if x['timestamp_ms']!=y['timestamp_ms'] or x['values']!=y['values']:raise ValueError('Original observation changed')
                    qualified=lambda r:[{k:v for k,v in x.items() if k!='attempt_id'} for x in r['attempts'] if x['verdict'] in ['CORRECT','ACCEPTABLE']]
                    if qualified(a)!=qualified(b):raise ValueError('Qualified attempt changed')
                    if semantic(a)!=semantic(b):
                        t=[y['timestamp_ms'] for x,y in zip(aa,bb) if semantic(x)!=semantic(y)]
                        changes.append(dict(file=path.name,before_counts=a['counts'],after_counts=b['counts'],changed_frames=len(t),first_changed_ms=min(t) if t else None,last_changed_ms=max(t) if t else None))
                rows.append(dict(configuration=tag,source_trace_files=traces,semantic_changes=changes))
    report=dict(schema='preparation-fault-trace-audit/1',configurations=rows,source_trace_files=sum(x['source_trace_files'] for x in rows),all_qualified_attempts_retained=True,all_original_observation_values_and_timestamps_unchanged=True,excluded_identity_fields=['compiler','reference_key'],human_live_rep_precision=None,new_rep_labels=0)
    dest=DATA/'preparation-fault-trace-comparison.json'
    if dest.exists():raise FileExistsError('Preserve observed comparison')
    dest.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
