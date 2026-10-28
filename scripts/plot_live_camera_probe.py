"""Original actual-camera frames and paired processing durations, no form labels."""
import csv,hashlib,json
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge/live-camera-probe-20261028'


def main():
    receipt=DATA/'original-camera-review.json'
    if receipt.exists():raise FileExistsError('Preserve original camera review')
    directory=DATA/'runtime-api35/evidence';summary=json.loads((directory/'summary.json').read_bytes());snapshots=json.loads((directory/'snapshots.json').read_bytes());analysis=json.loads((DATA/'analysis.json').read_bytes())
    with (directory/'frames.csv').open(newline='') as f:rows=[{k:v if k=='model_sha256' else None if v in ('null','') else int(v) for k,v in r.items()} for r in csv.DictReader(f)]
    start,end=summary['measurement_start_ns'],summary['measurement_end_ns'];selected=[r for r in rows if start<=r['result_ns']<=end]
    t=np.array([(r['result_ns']-start)/1e9 for r in selected]);total=np.array([(r['result_ns']-r['analyzer_arrival_ns'])/1e6 for r in selected]);native=np.array([r['inference_ns']/1e6 for r in selected])
    fig,axes=plt.subplots(2,1,figsize=(12,6),sharex=True);axes[0].plot(t,total,label='Analyzer-to-result',linewidth=.9);axes[0].plot(t,native,label='Native detect',linewidth=.9);axes[0].plot(t,total-native,label='Paired unattributed analyzer duration',linewidth=.9);axes[0].set_ylabel('Processing duration(ms)');axes[0].legend(fontsize=8);axes[0].grid(alpha=.25)
    edges=np.linspace(0,(end-start)/1e9,7);counts,_=np.histogram(t,bins=edges);axes[1].bar((edges[:-1]+edges[1:])/2,counts/np.diff(edges),width=np.diff(edges)*.9,color='steelblue');axes[1].set_ylabel('Delivered frames/s');axes[1].set_xlabel('Original result time since window start(s)');axes[1].grid(axis='y',alpha=.25)
    fig.suptitle(f'Current CameraX/API35 probe: {analysis["delivered_fps"]:.2f} delivered FPS | UNKNOWN capture clock\nShort diagnostic, no human-form accuracy or sustained-performance qualification',fontsize=11);fig.tight_layout();plot=DATA/'camera-processing-durations.png';assert not plot.exists();fig.savefig(plot,dpi=140);plt.close(fig)
    w,h=420,500;sheet=Image.new('RGB',(w*2,h*len(snapshots)),(20,20,20));font=ImageFont.load_default(size=12);records=[]
    for row,entry in enumerate(snapshots):
        rawpath=directory/(entry['file']+'.json');imagepath=directory/(entry['file']+'.png');raw=json.loads(rawpath.read_bytes());original=Image.open(imagepath).convert('RGB');overlay=original.copy();draw=ImageDraw.Draw(overlay)
        assert original.size==(raw['width'],raw['height']);points={p['id']:p for p in raw['raw_landmarks']};assert set(points)==set(range(33))
        for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
            ps=[points[i] for i in [a,b]]
            if any(p['world'] is None or min(p['visibility'] or 0,p['presence'] or 0)==0 for p in ps):continue
            good=all(min(p['visibility'],p['presence'])>=.65 and 0<=p['image'][0]<=1 and 0<=p['image'][1]<=1 for p in ps)
            draw.line([(p['image'][0]*overlay.width,p['image'][1]*overlay.height) for p in ps],fill=(0,230,100) if good else (140,140,140),width=3)
        for col,panel in enumerate([original,overlay]):
            panel.thumbnail((w,h-70));sheet.paste(panel,(col*w+(w-panel.width)//2,row*h+70));draw=ImageDraw.Draw(sheet)
            draw.text((col*w+4,row*h+3),'Original camera input' if col==0 else 'Pinned Full raw prediction',font=font,fill='white');draw.text((col*w+4,row*h+23),f'Source capture {raw["capture_ns"]}ns | status {raw["raw_status"]}',font=font,fill='white');draw.text((col*w+4,row*h+43),f'Result offset {(raw["result_ns"]-start)/1e9:.3f}s; capture clock UNKNOWN',font=font,fill='white')
        records.append(dict(file=entry['file'],capture_ns=raw['capture_ns'],result_ns=raw['result_ns'],raw_pose_timestamp_ms=raw['raw_pose_timestamp_ms'],timestamp_source=raw['timestamp_source'],raw_status=raw['raw_status'],observer_export_ns=entry['observer_export_ns'],input_sha256=hashlib.sha256(imagepath.read_bytes()).hexdigest(),raw_sha256=hashlib.sha256(rawpath.read_bytes()).hexdigest()))
    image=DATA/'original-camera-and-raw-predictions.png';assert not image.exists();sheet.save(image)
    receipt.write_text(json.dumps(dict(kind='original-virtual-camera-frames-and-raw-MP33-not-human-form-truth',observations=records,images=[dict(path=q.name,sha256=hashlib.sha256(q.read_bytes()).hexdigest()) for q in [plot,image]],new_human_labels=0,human_rep_precision=None),indent=2)+'\n',encoding='utf-8',newline='\n');print('Original camera frames, raw predictions and paired duration plots saved')


if __name__=='__main__':main()
