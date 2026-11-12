"""Static capacity distributions and pinned input/raw-output review, no accuracy labels."""
import json,hashlib
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge/live-stage-profile-20261028'


def main():
    receipt=DATA/'original-input-and-static-output-qa.json'
    if receipt.exists():raise FileExistsError('Preserve stage profile review')
    analysis=json.loads((DATA/'native-stage-capacity.json').read_bytes());evidence=DATA/'runtime-api35-export2/evidence';cases=analysis['cases']
    fields=['input_preparation_ns','native_detect_ns','other_estimator_ns','generic_update_ns','qualified_update_ns'];labels=['Prepared-input copy/scale','Native detect (includes owned input)','Other estimator work','Standalone generic processor','Processor with qualified counting']
    fig,axes=plt.subplots(2,2,figsize=(12,7),sharex=True)
    for ax,c in zip(axes.flat,cases):
        p50=[c['stages'][f]['p50_ms'] for f in fields];p95=[c['stages'][f]['p95_ms'] for f in fields];y=np.arange(len(fields))
        ax.barh(y,p50,color='steelblue',label='p50');ax.scatter(p95,y,marker='D',color='darkorange',label='p95',zorder=3);ax.set_yticks(y,labels,fontsize=8);ax.invert_yaxis();ax.set_xscale('log');ax.set_title(c['id']+f' ({c["width"]}x{c["height"]})',fontsize=10);ax.grid(axis='x',alpha=.25);ax.set_xlabel('Processing duration (ms),log scale');ax.legend(fontsize=8)
    fig.suptitle('API35 static-input capacity |24 measured replays/case | not CameraX latency/FPS\nGeneric and qualified processor bars are alternative whole updates, not additive stages',fontsize=11);fig.tight_layout();plot=DATA/'static-stage-distributions.png';assert not plot.exists();fig.savefig(plot,dpi=140);plt.close(fig)
    font=ImageFont.load_default(size=12);w,h=420,500;sheet=Image.new('RGB',(2*w,4*h),(20,20,20));records=[]
    for row,c in enumerate(cases):
        name=c['id'];path=evidence/(name+'-input.png');rawpath=evidence/(name+'-raw.json');raw=json.loads(rawpath.read_bytes());original=Image.open(path).convert('RGB');overlay=original.copy();draw=ImageDraw.Draw(overlay)
        assert original.size==(c['width'],c['height']);points={p['id']:p for p in raw['raw_landmarks']};assert set(points)==set(range(33))
        for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
            ps=[points[i] for i in [a,b]]
            if any(p['world'] is None or min(p['visibility'] or 0,p['presence'] or 0)==0 for p in ps):continue
            seen=all(min(p['visibility'],p['presence'])>=.65 and 0<=p['image'][0]<=1 and 0<=p['image'][1]<=1 for p in ps)
            draw.line([(p['image'][0]*overlay.width,p['image'][1]*overlay.height) for p in ps],fill=(0,230,100) if seen else (140,140,140),width=3)
        for col,panel in enumerate([original,overlay]):
            panel.thumbnail((w,h-75));sheet.paste(panel,(col*w+(w-panel.width)//2,row*h+75));draw=ImageDraw.Draw(sheet)
            draw.text((col*w+4,row*h+3),name+(' | prepared input' if col==0 else ' | raw Full overlay'),font=font,fill='white');draw.text((col*w+4,row*h+23),'Static replay model PTS '+str(raw['generated_model_timestamp_ms'])+'ms; not camera-result latency',font=font,fill='white');draw.text((col*w+4,row*h+43),'Source capture '+str(raw['source']['original_capture_ns'])+'ns',font=font,fill='white')
        records.append(dict(id=name,prepared_input_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),raw_output_sha256=hashlib.sha256(rawpath.read_bytes()).hexdigest(),source=raw['source'],generated_replay_timestamp_ms=raw['generated_model_timestamp_ms']))
    image=DATA/'original-input-static-output-review.png';assert not image.exists();sheet.save(image)
    receipt.write_text(json.dumps(dict(kind='original-prepared-input-and-raw-static-predictions-not-form-truth',cases=records,images=[dict(path=q.name,sha256=hashlib.sha256(q.read_bytes()).hexdigest()) for q in [plot,image]],new_human_labels=0,human_rep_precision=None),indent=2)+'\n',encoding='utf-8',newline='\n');print(plot);print(image)


if __name__=='__main__':main()
