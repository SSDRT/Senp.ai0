"""Original camera/raw-pose QA and fixed-order ablation plots, no form labels."""
import hashlib,json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'test-artifacts/exercise-knowledge/camera-preview-ablation-20261028'


def main():
    target=OUT/'original-frame-review.json'
    if target.exists():raise FileExistsError('Preserve original ablation review')
    directory=OUT/'runtime-api35/evidence';report=json.loads((OUT/'analysis.json').read_bytes());records=[]
    sheet=Image.new('RGB',(700,4*500),(20,20,20));font=ImageFont.load_default(size=12)
    for index,block in enumerate(report['blocks']):
        folder=directory/(f'block{index+1}-'+('preview' if block['requested_preview'] else 'analysis-only'))
        raw=json.loads((folder/'raw.json').read_bytes());original=Image.open(folder/'original.png').convert('RGB');assert original.size==(raw['width'],raw['height'])
        overlay=original.copy();draw=ImageDraw.Draw(overlay);points={p['id']:p for p in raw['raw_landmarks']};assert set(points)==set(range(33))
        for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
            p=[points[a],points[b]]
            if any(x['world'] is None or not x['visibility'] or not x['presence'] for x in p):continue
            good=all(min(x['visibility'],x['presence'])>=.65 and 0<=x['image'][0]<=1 and 0<=x['image'][1]<=1 for x in p)
            draw.line([(x['image'][0]*overlay.width,x['image'][1]*overlay.height) for x in p],fill='lime' if good else 'grey',width=3)
        for column,panel in enumerate([original,overlay]):
            panel.thumbnail((340,430));sheet.paste(panel,(column*350+(350-panel.width)//2,index*500+65));draw=ImageDraw.Draw(sheet)
            draw.text((column*350+4,index*500+3),f'Block{index+1} '+('Preview' if block['requested_preview'] else 'Analysis only')+(' | original' if column==0 else ' | raw Full'),font=font,fill='white')
            draw.text((column*350+4,index*500+23),f'Capture {raw["capture_ns"]}ns | {raw["raw_status"]}',font=font,fill='white')
            draw.text((column*350+4,index*500+43),f'{raw["width"]}x{raw["height"]}; timestamp source {raw["timestamp_source"]}',font=font,fill='white')
        records.append(dict(block=index+1,requested_preview=block['requested_preview'],capture_ns=raw['capture_ns'],result_ns=raw['result_ns'],raw_pose_timestamp_ms=raw['raw_pose_timestamp_ms'],timestamp_source=raw['timestamp_source'],raw_status=raw['raw_status'],original_sha256=hashlib.sha256((folder/'original.png').read_bytes()).hexdigest(),raw_sha256=hashlib.sha256((folder/'raw.json').read_bytes()).hexdigest()))
    image=OUT/'original-camera-and-raw-predictions.png';assert not image.exists();sheet.save(image)
    labels=['A1 preview','B1 analysis','B2 analysis','A2 preview'];fig,axes=plt.subplots(1,2,figsize=(12,4));axes[0].bar(labels,[b['delivered_fps'] for b in report['blocks']]);axes[0].set_ylabel('Delivered results/s over original45s windows');axes[0].tick_params(axis='x',rotation=15)
    axes[1].plot(labels,[b['p95_analyzer_to_result_ms'] for b in report['blocks']],marker='o',label='Analyzer p95');axes[1].plot(labels,[b['p95_native_detect_ms'] for b in report['blocks']],marker='o',label='Native detect p95');axes[1].set_ylabel('Wall duration(ms)');axes[1].legend();axes[1].tick_params(axis='x',rotation=15);fig.suptitle('Fixed ABBA camera-use-case diagnostic | no human labels or sustained-performance credit');fig.tight_layout();plot=OUT/'fixed-order-processing.png';assert not plot.exists();fig.savefig(plot,dpi=140);plt.close(fig)
    target.write_text(json.dumps(dict(kind='original-virtual-camera-input-and-raw-MP33-not-human-form-truth',records=records,images=[dict(path=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [image,plot]],new_human_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n');print('Four original camera frames/raw predictions and fixed-order timing chart saved')


if __name__=='__main__':main()
