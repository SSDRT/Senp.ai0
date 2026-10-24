"""Audit original camera timing/PSS records; unknown clocks never earn latency credit."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


def percentile95(values):
    if not values:return None
    ordered = sorted(values)
    return ordered[math.ceil(.95*len(ordered))-1]


def summarize(samples, memory, start, end, expected_model, queue_capacity, errors=0, crash_or_anr=0):
    if type(start) is not int or type(end) is not int or start<=0 or end<=start:
        raise ValueError('Original positive measurement bounds are required')
    if not re.fullmatch('[0-9a-f]{64}',expected_model):raise ValueError('Verified model identity required')
    measured = [s for s in samples if start<=s['result_ns']<=end]
    if not measured:raise ValueError('No completed camera observations in the declared interval')
    ordered = all(a['capture_ns']<b['capture_ns'] and a['result_ns']<b['result_ns'] and
                  a['result_ns']<=b['analyzer_arrival_ns'] for a,b in zip(samples,samples[1:]))
    timing_valid = all(s['capture_ns']>0 and s['analyzer_arrival_ns']>0 and
                       s['result_ns']>=s['analyzer_arrival_ns'] for s in measured)
    clock_valid = timing_valid and all(s['timestamp_source']==1 and s['analyzer_arrival_ns']>=s['capture_ns'] for s in measured)
    p95 = percentile95([(s['result_ns']-s['capture_ns'])/1e6 for s in measured]) if clock_valid else None
    duration = (end-start)/1e6
    fps = len(measured)/(duration/1000)
    mismatches = sum(s['model_sha256']!=expected_model for s in samples)
    def window(lo,hi):
        selected = [m for m in memory if lo<=m['elapsed_realtime_ns']<=hi]
        values = [m['total_pss_kib']/1024 for m in selected]
        supported = (len(selected)>=12 and selected[0]['elapsed_realtime_ns']-lo<=10_000_000_000 and
                     hi-selected[-1]['elapsed_realtime_ns']<=10_000_000_000 and
                     all(0<b['elapsed_realtime_ns']-a['elapsed_realtime_ns']<=10_000_000_000 for a,b in zip(selected,selected[1:])))
        return statistics.mean(values) if values else None,supported
    first,first_supported = window(start,start+120_000_000_000)
    last,last_supported = window(end-120_000_000_000,end+1_000_000_000)
    maximum = max((m['total_pss_kib']/1024 for m in memory),default=None)
    performance = (duration>=600000 and clock_valid and p95<=200 and fps>=5 and ordered and
                   queue_capacity==1 and mismatches==0 and errors==0 and crash_or_anr==0)
    memory_pass = (performance and first_supported and last_supported and maximum is not None and
                   maximum<=768 and first>0 and last<first*1.10)
    return {'schema':'senpai-live-soak-audit/1','kind':'device-specific-camera-performance-not-form-accuracy',
            'duration_ms':duration,'delivered_frames':len(measured),'delivered_fps':fps,'clock_comparable':clock_valid,
            'p95_capture_to_result_ms':p95,'p95_analyzer_to_result_ms':percentile95([
                (s['result_ns']-s['analyzer_arrival_ns'])/1e6 for s in measured]) if timing_valid else None,
            'p95_native_detect_ms':percentile95([s['inference_ns']/1e6 for s in measured]),
            'tracker_reset_increase':measured[-1]['tracker_resets']-measured[0]['tracker_resets'],
            'native_recreation_increase':(measured[-1]['native_recreations']-measured[0]['native_recreations'])
                if all('native_recreations' in s for s in measured) else None,
            'ordered_nonoverlapping_analysis':ordered,'maximum_queue':queue_capacity,
            'queue_bound_basis':'CameraX KEEP_ONLY_LATEST contract, checked with nonoverlapping analyzer samples',
            'model_mismatch_count':mismatches,'analyzer_error_count':errors,'crash_or_anr_count':crash_or_anr,
            'maximum_pss_mib':maximum,'first_window_pss_mib':first,'last_window_pss_mib':last,
            'pss_windows_supported':first_supported and last_supported,'post_warmup_windows_ms':120000,
            'performance_pass':performance,'memory_pass':memory_pass,
            'readiness_points_earned':5*int(performance)+5*int(memory_pass)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--logcat',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    summary_path = args.directory/'summary.json'
    original = json.loads(summary_path.read_text())
    with (args.directory/'frames.csv').open(newline='') as stream:
        rows = [{key:(value if key=='model_sha256' else int(value) if value not in ('null','') else None)
                 for key,value in row.items()} for row in csv.DictReader(stream)]
    memory = json.loads((args.directory/'memory.json').read_text())
    fatal = [line for line in args.logcat.read_text(errors='replace').splitlines() if re.search(r'FATAL EXCEPTION|Fatal signal|ANR in ',line)]
    if original['backpressure_strategy'] != 0:raise ValueError('Expected the recorded KEEP_ONLY_LATEST queue contract')
    result = summarize(rows,memory,original['measurement_start_ns'],original['measurement_end_ns'],original['model_sha256'],
                       original['maximum_queue_capacity'],len(original['analyzer_errors']),len(fatal))
    for field in ['duration_ms','delivered_fps','model_mismatch_count']:
        if not math.isclose(result[field],original[field],abs_tol=1e-6):raise ValueError('Android report disagrees with original records: '+field)
    if result['clock_comparable'] != original['clock_comparable']:raise ValueError('Camera clock audit disagrees')
    result.update(device=original['device'],api=original['api'],emulator=original['emulator'],
                  model_sha256=original['model_sha256'],compiler=original['compiler'],fatal_log_lines=fatal,
                  limits=['Virtual-scene camera input does not measure human-form accuracy.',
                          'An UNKNOWN sensor timestamp source cannot establish absolute capture-to-result latency.',
                          'Native detect time excludes tracker recreation; analyzer-to-result includes that cost.'])
    inputs = [summary_path,args.directory/'frames.csv',args.directory/'memory.json',args.directory/'errors.json',args.logcat]
    result['input_sha256'] = {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    args.output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
