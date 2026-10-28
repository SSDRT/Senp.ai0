"""Review static model inputs/predictions separately from actual camera-source images."""
import hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'test-artifacts/exercise-knowledge/frozen-pixel-camera-20261029'


def main():
    target=OUT/'input-review.json'
    if target.exists():raise FileExistsError('Preserve exact-input review')
    root=OUT/'runtime-api35/evidence';summary=json.loads((root/'summary.json').read_bytes());report=json.loads((OUT/'analysis.json').read_bytes());font=ImageFont.load_default(size=12)
    sheet=Image.new('RGB',(4*350,4*470),(20,20,20));cameraSheet=Image.new('RGB',(700,940),(20,20,20));records=[];cameraIndex=0
    for index,block in enumerate(summary['blocks']):
        folder=root/block['folder'];original=Image.open(folder/'model-input.png').convert('RGB');overlay=original.copy();raw=json.loads((folder/'raw-model-prediction.json').read_bytes());rows=json.loads((folder/'rows.json').read_bytes());sample=rows[8]
        assert raw['generated_model_timestamp_ms']==sample['generated_model_timestamp_ms']==536
        points={p['id']:p for p in raw['raw_landmarks']};assert set(points)==set(range(33));draw=ImageDraw.Draw(overlay)
        for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
            ps=[points[a],points[b]]
            if any(p['world'] is None or not p['visibility'] or not p['presence'] for p in ps):continue
            good=all(min(p['visibility'],p['presence'])>=.65 and 0<=p['image'][0]<=1 and 0<=p['image'][1]<=1 for p in ps)
            draw.line([(p['image'][0]*overlay.width,p['image'][1]*overlay.height) for p in ps],fill='lime' if good else 'grey',width=3)
        for row,panel in enumerate([original,overlay]):
            col=index%4;y=(index//4*2+row)*470;panel.thumbnail((340,410));sheet.paste(panel,(col*350+(350-panel.width)//2,y+55));draw=ImageDraw.Draw(sheet)
            draw.text((col*350+4,y+3),block['folder'],font=font,fill='white');draw.text((col*350+4,y+23),'Frozen model input' if row==0 else 'Raw static prediction | generated536ms',font=font,fill='white')
        record=dict(folder=block['folder'],model_input_sha256=hashlib.sha256((folder/'model-input.png').read_bytes()).hexdigest(),generated_model_timestamp_ms=536,raw_status=raw['raw_status'],actual_camera_capture_ns=sample['camera_capture_ns'],model_prediction_is_camera_source_prediction=False)
        if block['camera_active']:
            p=folder/'actual-camera-source-not-model-input.png';picture=Image.open(p).convert('RGB');picture.thumbnail((340,410));x=cameraIndex%2*350;y=cameraIndex//2*470;cameraSheet.paste(picture,(x+(350-picture.width)//2,y+55));draw=ImageDraw.Draw(cameraSheet);draw.text((x+4,y+3),block['folder']+' | camera source',font=font,fill='white');draw.text((x+4,y+23),f'Capture {sample["camera_capture_ns"]}ns; no pose overlay',font=font,fill='white');record['camera_source_sha256']=hashlib.sha256(p.read_bytes()).hexdigest();cameraIndex+=1
        records.append(record)
    inputs=OUT/'frozen-model-input-and-predictions.png';sources=OUT/'actual-camera-source-separate.png';assert not inputs.exists() and not sources.exists();sheet.save(inputs);cameraSheet.save(sources)
    fig,axes=plt.subplots(1,2,figsize=(12,4))
    for offset,ax in zip([0,4],axes):
        blocks=report['blocks'][offset:offset+4];labels=['quiet1','camera1','camera2','quiet2'];ax.plot(labels,[b['native_detect']['p95_ms'] for b in blocks],marker='o',label='Native wall p95');ax.plot(labels,[b['estimate_call']['p95_ms'] for b in blocks],marker='o',label='Estimate call wall p95');ax.set_title(blocks[0]['case_id']);ax.set_ylabel('Duration(ms)');ax.legend(fontsize=8);ax.grid(alpha=.25)
    fig.suptitle('Same frozen model pixels /generated VIDEO time | no live FPS or camera latency');fig.tight_layout();plot=OUT/'frozen-input-wall-times.png';assert not plot.exists();fig.savefig(plot,dpi=140);plt.close(fig)
    target.write_text(json.dumps(dict(kind='separate-static-model-predictions-and-actual-camera-source-provenance',records=records,images=[dict(path=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [inputs,sources,plot]],new_human_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n');print('Eight exact model-input/raw pairs and four separate timestamped camera sources saved')


if __name__=='__main__':main()
