"""Processing attribution diagnostics; never substitutes capacity for camera latency."""
import argparse,csv,hashlib,json,math
from pathlib import Path

FIELDS=['input_preparation_ns','estimate_call_ns','native_detect_ns','other_estimator_ns','generic_update_ns','qualified_update_ns']


def quantile(values,fraction):
    if not values:raise ValueError('Empty measurement distribution')
    return sorted(values)[max(0,math.ceil(len(values)*fraction)-1)]


def stats(values):
    if any(not isinstance(x,int) or x<0 for x in values):raise ValueError('Expected nonnegative nanosecond durations')
    return dict(samples=len(values),p50_ms=quantile(values,.5)/1e6,p95_ms=quantile(values,.95)/1e6,mean_ms=sum(values)/len(values)/1e6)


def original_soak(summary,rows):
    start,end=summary['measurement_start_ns'],summary['measurement_end_ns']
    if start<=0 or end<=start:raise ValueError('Original window required')
    if any(a['result_ns']>b['analyzer_arrival_ns'] for a,b in zip(rows,rows[1:])):raise ValueError('Concurrent analyzer samples')
    selected=[r for r in rows if start<=r['result_ns']<=end]
    if not selected or any(r['model_sha256']!=summary['model_sha256'] for r in rows):raise ValueError('Missing or mismatched model samples')
    total=[r['result_ns']-r['analyzer_arrival_ns'] for r in selected];native=[r['inference_ns'] for r in selected]
    if any(n<0 or t<n for t,n in zip(total,native)):raise ValueError('Invalid nested measurement durations')
    # Pair before taking a percentile: percentile(total)-percentile(native) is not a residual distribution.
    residual=[t-n for t,n in zip(total,native)]
    capture_comparable=all(r['timestamp_source']==1 and 0<r['capture_ns']<=r['analyzer_arrival_ns'] for r in selected)
    return dict(kind='historical-camera-duration-attribution-not-bottleneck-proof',compiler=summary['compiler'],measured_samples=len(selected),measurement_start_ns=start,measurement_end_ns=end,delivered_fps=len(selected)/((end-start)/1e9),analyzer_total=stats(total),native_detect=stats(native),unattributed_analyzer=stats(residual),capture_clock_comparable=capture_comparable,p95_capture_to_result_ms=quantile([r['result_ns']-r['capture_ns'] for r in selected],.95)/1e6 if capture_comparable else None,readiness_points_earned=0,human_rep_precision=None)


def native_profile(profile):
    if (profile['schema']!='senpai-live-stage-profile/1' or profile['duration_clock']!='System.nanoTime' or profile['real_camera_capture_measured'] or not profile['generated_replay_timestamps']):raise ValueError('Static diagnostic identity changed')
    if len(profile['cases'])!=4:raise ValueError('Four frozen input cases required')
    result=[]
    for case in profile['cases']:
        rows=case['rows']
        if len(rows)!=32 or any(r['index']!=i or r['warmup']!=(i<8) or r['generated_model_timestamp_ms']!=i*67 for i,r in enumerate(rows)):raise ValueError('Frozen warmup/window changed')
        for row in rows:
            if row['estimate_call_ns']!=row['native_detect_ns']+row['other_estimator_ns']:raise ValueError('Nested estimator duration mismatch')
            if row['processor_order']!=('generic-first' if row['index']%2==0 else 'qualified-first'):raise ValueError('Counterbalanced order changed')
        measured=rows[8:];s={field:stats([r[field] for r in measured]) for field in FIELDS}
        result.append(dict(id=case['case']['id'],width=case['prepared_width'],height=case['prepared_height'],measured_samples=24,stages=s,raw_status_counts={k:sum(r['raw_status']==k for r in measured) for k in sorted({r['raw_status'] for r in measured})},native_recreation_increase=measured[-1]['native_recreation_count']-measured[0]['native_recreation_count']))
    return dict(schema='senpai-live-stage-capacity-analysis/1',kind='static-replay-processing-capacity-not-live-camera-performance',api=profile['api'],device=profile['device'],compiler=profile['compiler'],model_sha256=profile['model_sha256'],cases=result,real_camera_capture_measured=False,p95_capture_to_result_ms=None,live_delivered_fps=None,readiness_points_earned=0,human_rep_precision=None)


def main():
    p=argparse.ArgumentParser();p.add_argument('--soak',type=Path);p.add_argument('--profile',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    if (a.soak is None)==(a.profile is None):raise ValueError('Choose one measurement domain')
    if a.output.exists():raise FileExistsError('Preserve measurement analysis')
    if a.soak:
        summary=json.loads((a.soak/'summary.json').read_bytes())
        with (a.soak/'frames.csv').open(newline='') as f:rows=[{k:(v if k=='model_sha256' else None if v in ('null','') else int(v)) for k,v in r.items()} for r in csv.DictReader(f)]
        result=original_soak(summary,rows);inputs=[a.soak/'summary.json',a.soak/'frames.csv']
    else:result=native_profile(json.loads(a.profile.read_bytes()));inputs=[a.profile]
    result['input_sha256']={str(q):hashlib.sha256(q.read_bytes()).hexdigest() for q in inputs};a.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(result,indent=2))


if __name__=='__main__':main()
