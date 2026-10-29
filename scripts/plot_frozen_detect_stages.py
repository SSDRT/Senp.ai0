"""Review distinct original camera sources and frozen-input raw native predictions."""
import argparse
import hashlib
import json
import math
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True)
    a=p.parse_args();output=a.directory/'qa';output.mkdir(exist_ok=False)
    summary=json.loads((a.directory/'summary.json').read_bytes());receipts=[]
    fig,axes=plt.subplots(2,4,figsize=(18,10),layout='constrained')
    cameras=[]
    for ax,meta in zip(axes.flat,summary['blocks']):
        folder=a.directory/meta['folder'];raw=json.loads((folder/'raw-model-prediction.json').read_bytes())
        rows=json.loads((folder/'rows.json').read_bytes());sample=rows[8]
        if raw['generated_model_timestamp_ms']!=sample['generated_model_timestamp_ms'] or raw['native_result_timestamp_ms']!=sample['native_result_timestamp_ms']:
            raise ValueError('Snapshot/result model-time mismatch')
        if raw['native_pose_count']!=len(raw['poses']) or raw['native_pose_count']!=sample['native_pose_count']:
            raise ValueError('Raw native pose count changed')
        image_path=folder/'model-input.png';image=Image.open(image_path)
        if image.size!=(meta['model_input_width'],meta['model_input_height']):raise ValueError('Input dimensions changed')
        ax.imshow(image);ax.set_axis_off()
        for pose in raw['poses']:
            points=pose['raw_landmarks']
            if [r['id'] for r in points]!=list(range(33)):raise ValueError('Original raw33 expected for every detected pose')
            for r in points:
                x,y,z=r['image']
                if all(type(v) in (int,float) and math.isfinite(v) for v in [x,y,z]):
                    ax.plot(x*image.width,y*image.height,'.',color='cyan',markersize=3)
            # Drawing bounds clip only the diagnostic canvas; original raw coordinates remain intact.
        ax.set_xlim(0,image.width);ax.set_ylim(image.height,0)
        ax.set_title(f"{meta['case_id']} block{meta['block_order']} | {'camera active' if meta['camera_active'] else 'quiet'}\nFrozen model time {raw['native_result_timestamp_ms']}ms | native poses {len(raw['poses'])}",fontsize=10)
        receipts.append(dict(folder=meta['folder'],model_input_sha256=hashlib.sha256(image_path.read_bytes()).hexdigest(),raw_json_sha256=hashlib.sha256((folder/'raw-model-prediction.json').read_bytes()).hexdigest(),generated_model_timestamp_ms=sample['generated_model_timestamp_ms'],native_pose_count=raw['native_pose_count']))
        if meta['camera_active']:
            path=folder/'actual-camera-source-not-model-input.png'
            cameras.append((path,sample['camera_capture_ns'],meta))
    fig.suptitle('Frozen static model inputs + complete unqualified native33 evidence | No human rep labels or camera-pose inference claim',fontsize=13)
    fig.savefig(output/'frozen-model-inputs-raw.png',dpi=120);plt.close(fig)
    if len(cameras)!=4:raise ValueError('Four separately recorded original camera sources required')
    fig,axes=plt.subplots(1,4,figsize=(17,7),layout='constrained')
    for ax,(path,capture,meta) in zip(axes,cameras):
        ax.imshow(Image.open(path));ax.set_axis_off();ax.set_title(f"{meta['case_id']} block{meta['block_order']}\nOriginal camera capture_ns {capture}",fontsize=10)
    fig.suptitle('Actual camera-source images: these pixels were NOT model inputs for the above predictions | Capture clock not verified here',fontsize=12)
    fig.savefig(output/'separate-original-camera-sources.png',dpi=120);plt.close(fig)
    report=dict(schema='senpai-frozen-detect-stages-qa/1',snapshots=receipts,
        original_camera_sources=[dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),camera_capture_ns=capture) for path,capture,meta in cameras],
        native_raw_outputs_not_assigned_to_camera_sources=True,pose_coords_not_clamped_or_repaired=True,new_human_labels=0,human_rep_precision=None)
    (output/'receipts.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('Eight frozen-input/native-result pairs and four distinct original camera sources reviewed')


if __name__=='__main__':main()
