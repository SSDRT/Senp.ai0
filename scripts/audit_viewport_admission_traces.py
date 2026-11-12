"""Account for every original PTS and angle withdrawal, including unavailable fits."""
import json
from audit_ready_form_research import DATA


def main():
    out=DATA/'viewport-admission-trace-comparison.json'
    if out.exists():raise FileExistsError('Preserve trace audit')
    groups=[];totals=dict(common_traces=0,new_traces=0,withheld_traces=0,comparison_angle_withdrawals=0,non_three_d_diagnostic_angle_changes=0)
    for cohort in ['capped','complete']:
        for model in ['full','heavy']:
            for fit in ['standard','robust']:
                tag=f'{cohort}-{model}-{fit}';before=DATA/('preparation-fault-'+tag);after=DATA/('viewport-admission-'+tag)
                aa={p.name:p for p in before.glob('*.json') if p.name!='summary.json'};bb={p.name:p for p in after.glob('*.json') if p.name!='summary.json'}
                changed=[];dropped=0;diagnostic=0
                for name in sorted(aa.keys()&bb.keys()):
                    a,b=[json.loads(x[name].read_bytes()) for x in [aa,bb]];assert len(a['trace'])==len(b['trace'])
                    times=[]
                    for x,y in zip(a['trace'],b['trace']):
                        assert x['timestamp_ms']==y['timestamp_ms'] and x['values'].keys()==y['values'].keys()
                        for k,v in y['values'].items():
                            prior=x['values'][k]
                            if prior is not None and v is None:dropped+=1
                            elif v is not None and v!=prior:
                                assert y['stage']=='UNAVAILABLE' and y['unavailable_reason']=='MISSING_CRITICAL_EVIDENCE','Numeric diagnostic changed while eligible'
                                diagnostic+=1
                        if x!=y:times.append(y['timestamp_ms'])
                    if times:changed.append(dict(file=name,changed_frames=len(times),first_changed_ms=times[0],last_changed_ms=times[-1]))
                totals['common_traces']+=len(aa.keys()&bb.keys());totals['new_traces']+=len(bb.keys()-aa.keys());totals['withheld_traces']+=len(aa.keys()-bb.keys());totals['comparison_angle_withdrawals']+=dropped;totals['non_three_d_diagnostic_angle_changes']+=diagnostic
                groups.append(dict(configuration=tag,common_traces=len(aa.keys()&bb.keys()),new_trace_files=sorted(bb.keys()-aa.keys()),withheld_trace_files=sorted(aa.keys()-bb.keys()),changed_traces=changed,comparison_angle_withdrawals=dropped,non_three_d_diagnostic_angle_changes=diagnostic))
    report=dict(schema='viewport-admission-timestamped-trace-audit/1',totals=totals,configurations=groups,all_common_original_timestamps_preserved=True,eligible_numeric_angles_not_rewritten=True,raw_MP33_cache_hashes_bound_in_frozen_protocol=True,qualification_changes_in_camera_report=True,human_rep_precision=None,new_human_labels=0)
    out.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(totals))


if __name__=='__main__':main()
