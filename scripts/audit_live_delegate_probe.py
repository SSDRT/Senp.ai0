"""Audit frozen static-image native timings without awarding live/accuracy points."""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def p95(values):
    ordered=sorted(values)
    return ordered[math.ceil(.95*len(ordered))-1]


def audit(report, protocol):
    if (report.get('schema')!='senpai-native-delegate-probe/1' or
            report.get('synthetic_diagnostic_timebase') is not True or
            report.get('real_camera_inference') is not False or
            report.get('warmup_frames')!=2 or report.get('measured_frames')!=8 or
            report.get('api')!=protocol['api'] or report.get('abi')!=protocol['abi'] or
            report.get('fixture_sha256')!=protocol['fixture_sha256']):
        raise ValueError('Probe does not match frozen static diagnostic scope')
    keys=[(r['model'],r['scene'],r['requested_delegate']) for r in report['rows']]
    expected=set(itertools.product(['FULL','HEAVY'],['pose','blank'],['CPU','GPU']))
    if len(keys)!=8 or set(keys)!=expected:raise ValueError('Missing or duplicated probe condition')
    indexed={};results=[]
    for row in report['rows']:
        if row['model_sha256']!=protocol['model_sha256'][row['model']]:raise ValueError('Model identity changed')
        if len(row['frames'])!=10:raise ValueError('Missing native observation')
        if not finite(row['creation_ns']) or row['creation_ns']<=0:raise ValueError('Invalid graph creation timing')
        for i,frame in enumerate(row['frames']):
            if frame['index']!=i or frame['diagnostic_timestamp_ms']!=i*67 or frame['warmup']!=(i<2):
                raise ValueError('Diagnostic timing or warmup changed')
            if (not finite(frame['total_call_ns']) or not finite(frame['inference_ns']) or
                    not frame['total_call_ns']>=frame['inference_ns']>0):
                raise ValueError('Invalid or incomplete native timing')
            actual=frame['actual_delegate'];reason=frame['fallback_reason']
            if actual not in {'CPU','GPU'}:raise ValueError('Unknown actual delegate')
            if row['requested_delegate']=='CPU' and (actual!='CPU' or reason is not None):
                raise ValueError('CPU condition silently changed delegate')
            if row['requested_delegate']=='GPU' and actual=='CPU' and not isinstance(reason,str):
                raise ValueError('CPU fallback needs explicit diagnostics')
            if actual=='GPU' and reason is not None:raise ValueError('Contradictory GPU fallback diagnostics')
            points=frame['raw_landmarks']
            if row['scene']=='blank':
                if points:raise ValueError('No-person placeholders cannot be raw observed MP33')
            else:
                if len(points)!=33 or [p['id'] for p in points]!=list(range(33)):
                    raise ValueError('Incomplete or reordered native MP33')
                for point in points:
                    for field in ['image','world']:
                        if (not isinstance(point[field],list) or len(point[field])!=3 or
                                not all(finite(v) for v in point[field])):raise ValueError('Native coordinates missing/nonfinite')
                    if not all(finite(point[k]) and 0<=point[k]<=1 for k in ['visibility','presence']):
                        raise ValueError('Native confidence changed or missing')
        measured=row['frames'][2:]
        result=dict(model=row['model'],scene=row['scene'],requested_delegate=row['requested_delegate'],
            actual_delegates=sorted({f['actual_delegate'] for f in measured}),
            GPU_measured_frames=sum(f['actual_delegate']=='GPU' for f in measured),
            fallback_reasons=sorted({f['fallback_reason'] for f in row['frames'] if f['fallback_reason']}),
            creation_ms=row['creation_ns']/1e6,p95_total_call_ms=p95([f['total_call_ns']/1e6 for f in measured]),
            p95_native_inference_ms=p95([f['inference_ns']/1e6 for f in measured]),
            measured_frames=8,native_recreations_at_end=row['frames'][-1]['native_recreations'])
        results.append(result);indexed[row['model'],row['scene'],row['requested_delegate']]=(row,result)
    comparisons=[]
    for model,scene in itertools.product(['FULL','HEAVY'],['pose','blank']):
        cpu,cpu_stats=indexed[model,scene,'CPU'];gpu,gpu_stats=indexed[model,scene,'GPU']
        errors=dict(image=0.0,world=0.0,visibility=0.0,presence=0.0)
        for a,b in zip(cpu['frames'][2:],gpu['frames'][2:]):
            for x,y in zip(a['raw_landmarks'],b['raw_landmarks']):
                for field in ['image','world']:
                    errors[field]=max(errors[field],max(abs(u-v) for u,v in zip(x[field],y[field])))
                for field in ['visibility','presence']:errors[field]=max(errors[field],abs(x[field]-y[field]))
        real_gpu=gpu_stats['GPU_measured_frames']==8 and not gpu_stats['fallback_reasons']
        comparisons.append(dict(model=model,scene=scene,actual_GPU_measured=real_gpu,
            maximum_raw_field_error=errors,
            within_existing_native_parity_tolerance=all(v<=protocol['native_parity_tolerance'] for v in errors.values()),
            CPU_to_requested_GPU_total_time_ratio=cpu_stats['p95_total_call_ms']/gpu_stats['p95_total_call_ms'],
            ratio_is_GPU_acceleration_evidence=real_gpu))
    return dict(schema='senpai-native-delegate-probe-audit/1',
        kind='static-native-inference-diagnostic-not-live-camera-performance-or-human-rep-precision',
        rows=results,comparisons=comparisons,actual_live_rep_precision=None,
        performance_readiness_points=0,real_camera_measured=False,ten_minute_soak=False,
        production_changed=False,default_delegate_changed=False,raw_network_weights_changed=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--raw',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError('Preserve previous probe audit')
    result=audit(json.loads(args.raw.read_bytes()),json.loads(args.protocol.read_bytes()))
    result['raw_sha256']=hashlib.sha256(args.raw.read_bytes()).hexdigest()
    result['protocol_sha256']=hashlib.sha256(args.protocol.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in result.items() if k!='rows'},indent=2))


if __name__=='__main__':main()
