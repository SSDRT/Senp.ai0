"""Same-frame colour fidelity gate; neither a pose benchmark nor a speed test."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image


def analyze(summary, errors):
    if (summary['schema']!='senpai-camerax-conversion-parity/1' or summary['requested_pairs']!=6 or
            summary['no_pose_inference_during_pairs'] is not True or summary['production_changed'] is not False or errors or
            len(summary['rows'])!=6):raise ValueError('Complete frozen six-pair diagnostic required')
    previous=-1;pixel_equal=True;source_unchanged=True;scope_supported=True
    for i,row in enumerate(summary['rows']):
        if (row['index']!=i or type(row['source_capture_ns']) is not int or row['source_capture_ns']<=previous or
                row['source_format']!=35 or (row['source_width'],row['source_height'],row['rotation_degrees'])!=(640,480,90) or
                (row['prepared_width'],row['prepared_height'])!=(480,640) or row['total_pixels']!=307200):
            raise ValueError('Original identity/format/dimensions/rotation changed')
        previous=row['source_capture_ns']
        d,m,s=(row[k] for k in ['different_pixels','maximum_channel_difference','absolute_channel_difference_sum'])
        if (any(type(v) is not int or v<0 for v in (d,m,s)) or d>row['total_pixels'] or m>255 or s>1020*d or
                ((d==0)!=(m==0)) or ((d==0)!=(s==0))):raise ValueError('Invalid measured channel disagreement')
        exact=row['direct_argb_sha256']==row['via_rgba_argb_sha256']
        if exact!=(d==0):raise ValueError('Measured disagreement does not match original pixel hashes')
        if len(row['source_planes'])!=3 or [p['index'] for p in row['source_planes']]!=[0,1,2]:raise ValueError('Original three YUV planes required')
        if type(row['actual_one_pixel_shift_enabled']) is not bool or type(row['source_plane_bytes_unchanged']) is not bool:
            raise ValueError('Explicit quirk/byte provenance required')
        pixel_equal &= exact;source_unchanged &= row['source_plane_bytes_unchanged']
        scope_supported &= not row['actual_one_pixel_shift_enabled'] and row['source_crop']=='[0,0][640,480]'
    passed=pixel_equal and source_unchanged and scope_supported
    return dict(schema='senpai-camerax-conversion-parity-analysis/1',pairs=6,
        all_six_prepared_ARGB_arrays_exactly_equal=pixel_equal,all_original_YUV_plane_bytes_unchanged=source_unchanged,
        measured_unshifted_full_crop_scope=scope_supported,pixel_fidelity_gate_pass=passed,
        decision='SAME_FRAME_PIXEL_GATE_ONLY_PASSED' if passed else 'STOP_BEFORE_PERFORMANCE_OR_PROMOTION',
        production_optimization_promoted=False,live_fps=None,capture_to_result_latency=None,
        human_rep_precision=None,readiness_points_earned=0,rows=summary['rows'])


def png_argb_sha(path):
    with Image.open(path) as image:
        rgba=image.convert('RGBA').tobytes()
        argb=bytearray(len(rgba));argb[0::4]=rgba[3::4];argb[1::4]=rgba[0::4];argb[2::4]=rgba[1::4];argb[3::4]=rgba[2::4]
        return hashlib.sha256(argb).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve original fidelity decision')
    summary=json.loads((a.directory/'summary.json').read_bytes());report=analyze(summary,json.loads((a.directory/'errors.json').read_bytes()))
    for i,row in enumerate(summary['rows']):
        folder=a.directory/f'pair{i+1}'
        if row!=json.loads((folder/'receipt.json').read_bytes()):raise ValueError('Pair receipt differs from original summary')
        for plane in row['source_planes']:
            raw=(folder/f"source-plane{plane['index']}.bin").read_bytes()
            if len(raw)!=plane['bytes'] or hashlib.sha256(raw).hexdigest()!=plane['sha256']:raise ValueError('Original source plane changed')
        if png_argb_sha(folder/'direct-yuv-upright.png')!=row['direct_argb_sha256'] or png_argb_sha(folder/'via-rgba-upright.png')!=row['via_rgba_argb_sha256']:
            raise ValueError('Lossless original image does not match measured pixels')
    report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],
                  input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in {'rows','input_sha256'}},indent=2))


if __name__=='__main__':main()
