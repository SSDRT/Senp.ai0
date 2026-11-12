"""Passive development-only withholding audit; no tuning, inferred faults or labels."""
import gzip,hashlib,json,math
from collections import Counter
from pathlib import Path
from fetch_uiprmd import ROOT,checksum
from analyze_uiprmd_evidence import analyze
from evaluate_uiprmd_development import QUALIFIED,match_events

OUT=ROOT/'test-artifacts/exercise-knowledge/frontal-arm-audit-20261029'
INPUT=ROOT/'test-artifacts/exercise-knowledge/frontal-arm-projection-20261029'


def validate_manifest(manifest,protocol_sha):
    expected={(s,m,c) for s in ['01','02','03','04'] for m in ['m01','m07'] for c in [True,False]}
    keys=[(r['subject'],r['movement'],r['correct']) for r in manifest['records']]
    if (manifest['schema']!='uiprmd-frontal-audit/1' or manifest['compiler']!='qualified-rep-compiler/9' or
        manifest['scope']!='development' or manifest['protocol_sha256']!=protocol_sha or
        manifest['validation_accessed'] or manifest['final_test_accessed'] or manifest['production_changed'] or
        len(keys)!=16 or set(keys)!=expected):
        raise PermissionError('Frozen complete development audit required; no validation/final scope')


def numeric(value):return type(value) in (int,float) and math.isfinite(value)


def observed_span(rows,predicate):
    """Span between adjacent 10ms observations, not interpolation or engine dwell."""
    longest=0;first=None;previous=None
    for row in rows:
        now=row['timestamp_ms']
        if previous is not None and now<=previous:raise ValueError('Duplicate/backward original sample')
        if not predicate(row):first=None
        elif first is None or previous is None or now-previous!=10:first=now
        else:longest=max(longest,now-first)
        previous=now
    return longest


def episode_inputs(record,episode):
    rows=record['frames']
    if any(x['timestamp_ms']!=i*10 for i,x in enumerate(rows)):raise ValueError('Original 100Hz grid changed')
    start,end=episode['start_ms'],episode['end_ms']
    if start<0 or end<start or end>rows[-1]['timestamp_ms']:raise ValueError('Original episode outside recording')
    selected=[x for x in rows if start<=x['timestamp_ms']<=end]
    prelude=[x for x in rows if max(0,start-1000)<=x['timestamp_ms']<start]
    def visible(row):return bool(row['critical_values']) and all(numeric(v) and 0<=v<=180 for v in row['critical_values'].values())
    def ready(row):return visible(row) and numeric(row['progress']) and -.35<=row['progress']<=record['ready_progress']
    def far(row):return visible(row) and numeric(row['progress']) and row['progress']>=record['required_progress']
    adjacent=[min(a['progress'],b['progress']) for a,b in zip(selected,selected[1:]) if b['timestamp_ms']-a['timestamp_ms']==10 and numeric(a['progress']) and numeric(b['progress'])]
    return dict(original_input_frames=len(selected),stage_counts=dict(Counter(x['stage'] for x in selected)),prelude_1000ms_stage_counts=dict(Counter(x['stage'] for x in prelude)),critical_numeric_missing_frames=sum(not visible(x) for x in selected),maximum_output_progress=max((x['progress'] for x in selected if numeric(x['progress'])),default=None),maximum_adjacent_output_progress=max(adjacent,default=None),observed_ready_input_span_ms=observed_span(selected,ready),observed_far_input_span_ms=observed_span(selected,far),these_input_spans_are_not_engine_confirmations=True)


def read_records():
    protocol=ROOT/'models/uiprmd-frontal-audit-protocol.json';spec=json.loads(protocol.read_bytes())
    for row in spec['receipts']:
        p=ROOT/row['path'];actual=hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() if 'sha256_lf' in row else checksum(p,'sha256')
        if actual!=row.get('sha256_lf',row.get('sha256')):raise ValueError('Frozen input/source changed: '+row['path'])
    manifest=json.loads((OUT/'manifest.json').read_bytes());validate_manifest(manifest,checksum(protocol,'sha256'))
    if checksum(INPUT/'development/manifest.json','sha256')!=manifest['source_manifest_sha256']:raise ValueError('Frozen projection export changed')
    previous=json.loads((INPUT/'frontal_arm-replay.json').read_bytes())['rows'];records=[]
    for entry in manifest['records']:
        name=f"{entry['movement']}-s{entry['subject']}-{'correct' if entry['correct'] else 'incorrect'}.json.gz"
        if entry['file']!=name:raise PermissionError('Unexpected audit filename')
        p=OUT/name
        if p.resolve().parent!=OUT.resolve() or checksum(p,'sha256')!=entry['sha256']:raise ValueError('Audit path/identity changed')
        with gzip.open(p,'rt',encoding='utf-8') as f:record=json.load(f)
        if (record['schema']!='uiprmd-frontal-audit-record/1' or not record['comparison_outputs_identical_with_and_without_observer'] or
            not record['all_previous_attempt_fields_equal'] or any(record[k]!=entry[k] for k in ['subject','movement','correct']) or len(record['episodes'])!=10):raise ValueError('Observer identity/parity changed')
        old=next(x for x in previous if all(x[k]==record[k] for k in ['subject','movement','correct']))
        if record['attempts']!=old['attempts'] or record['episodes']!=old['episodes'] or record['reference_key']!=old['reference_key']:raise ValueError('Old outcomes/labels changed')
        records.append(record)
    return spec,records


def main():
    target=OUT/'analysis.json'
    if target.exists():raise FileExistsError('Preserve original diagnostic analysis')
    _,records=read_records();result=analyze(records)
    previous=json.loads((INPUT/'comparison.json').read_bytes())['reports']['frontal_arm']
    for key in ['counts','precision','good_rep_recall','assessable_coverage','rep_results']:
        if result['metrics'][key]!=previous[key]:raise ValueError('Diagnostic metrics differ from original frozen screen')
    details=[];withheld=Counter();noevents=Counter()
    for row in records:
        matches,_=match_events(row['episodes'],row['attempts'])
        for i,episode in enumerate(row['episodes']):
            matched=matches.get(i);attempt=row['attempts'][matched['attempt_index']] if matched else None;verdict=attempt['verdict'] if attempt else 'NO_EVENT'
            summary=episode_inputs(row,episode)
            details.append(dict(subject=row['subject'],movement=row['movement'],correct=episode['correct'],episode=episode['episode'],verdict=verdict,**summary))
            if episode['correct'] and verdict not in QUALIFIED:
                if attempt:
                    for reason in attempt['reasons']:withheld[row['movement']+'/'+reason]+=1
                else:noevents[row['movement']]+=1
    result.update(schema='uiprmd-frontal-audit-analysis/1',kind='passive-development-diagnostic-not-new-labels-or-camera-accuracy',episode_input_diagnostics=details,withheld_good_reasons_overlap=dict(withheld),good_no_event_counts=dict(noevents),all_original_labels_retained=True,all_previous_attempt_fields_equal=True,quality_head_trained=False,thresholds_changed=False,validation_accessed=False,final_test_accessed=False,production_changed=False,readiness_points_earned=0,human_rep_precision=None)
    target.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(withheld_good_reasons_overlap=dict(withheld),good_no_event_counts=dict(noevents),metrics={k:result['metrics'][k] for k in ['counts','precision','good_rep_recall','assessable_coverage']}),indent=2))


if __name__=='__main__':main()
