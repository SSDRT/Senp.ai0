"""Original camera/raw33 evidence for input-format ABBA; not human-form truth."""
import hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'test-artifacts/exercise-knowledge/camera-input-format-20261029'


def main():
    receipt=OUT/'original-review.json'
    if receipt.exists():raise FileExistsError('Preserve mode comparison review')
    report=json.loads((OUT/'analysis.json').read_bytes());root=OUT/'runtime-api35/evidence';font=ImageFont.load_default(size=12);sheet=Image.new('RGB',(700,2000),(20,20,20));screens=Image.new('RGB',(800,1800),(20,20,20));records=[]
    for index,b in enumerate(report['blocks']):
        folder=root/(f'block{index+1}-'+b['requested_input_format'].lower());b=dict(b,screen_capture_ns=json.loads((folder/'summary.json').read_bytes())['screen_capture_ns']);raw=json.loads((folder/'raw.json').read_bytes());original=Image.open(folder/'original.png').convert('RGB');assert raw['input_image']['capture_ns']==raw['capture_ns'];assert raw['input_image']['format']==(1 if b['requested_input_format']=='RGBA' else 35);overlay=original.copy();assert original.size==(raw['width'],raw['height']);points={p['id']:p for p in raw['raw_landmarks']};assert set(points)==set(range(33));draw=ImageDraw.Draw(overlay)
        for a,c in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
            ps=[points[a],points[c]]
            if any(p['world'] is None or not p['visibility'] or not p['presence'] for p in ps):continue
            good=all(min(p['visibility'],p['presence'])>=.65 and 0<=p['image'][0]<=1 and 0<=p['image'][1]<=1 for p in ps)
            draw.line([(p['image'][0]*overlay.width,p['image'][1]*overlay.height) for p in ps],fill='lime' if good else 'grey',width=3)
        for col,panel in enumerate([original,overlay]):
            panel.thumbnail((340,430));sheet.paste(panel,(col*350+(350-panel.width)//2,index*500+65));draw=ImageDraw.Draw(sheet);draw.text((col*350+4,index*500+3),f'Block{index+1} {b["requested_input_format"]} | '+('original' if col==0 else 'raw Full'),font=font,fill='white');draw.text((col*350+4,index*500+23),f'Capture {raw["capture_ns"]}ns | {raw["raw_status"]}',font=font,fill='white');draw.text((col*350+4,index*500+43),f'{b["preview_view_start"]} / source clock {raw["timestamp_source"]}',font=font,fill='white')
        screenpath=folder/'screen.png';saved=screenpath.exists()
        if saved:
            picture=Image.open(screenpath).convert('RGB');picture.thumbnail((390,835));x=index%2*400;y=index//2*900;screens.paste(picture,(x+(400-picture.width)//2,y+50));draw=ImageDraw.Draw(screens);draw.text((x+4,y+3),f'Block{index+1} {b["requested_input_format"]} | {b["preview_view_start"]}',font=font,fill='white');draw.text((x+4,y+23),f'Screen time {b["screen_capture_ns"]}ns',font=font,fill='white')
        records.append(dict(block=index+1,requested_input_format=b['requested_input_format'],preview_view_start=b['preview_view_start'],preview_view_end=b['preview_view_end'],capture_ns=raw['capture_ns'],result_ns=raw['result_ns'],raw_pose_timestamp_ms=raw['raw_pose_timestamp_ms'],raw_status=raw['raw_status'],original_sha256=hashlib.sha256((folder/'original.png').read_bytes()).hexdigest(),raw_sha256=hashlib.sha256((folder/'raw.json').read_bytes()).hexdigest(),screen_sha256=hashlib.sha256(screenpath.read_bytes()).hexdigest() if saved else None))
    inputs=OUT/'original-camera-and-raw-predictions.png';display=OUT/'actual-preview-screens.png';assert not inputs.exists() and not display.exists();sheet.save(inputs);screens.save(display)
    fig,axes=plt.subplots(1,2,figsize=(12,4));labels=['A1 RGBA','B1 YUV','B2 YUV','A2 RGBA'];axes[0].bar(labels,[b['delivered_fps'] for b in report['blocks']]);axes[0].set_ylabel('Delivered actual-camera results/s');axes[1].plot(labels,[b['p95_analyzer_to_result_ms'] for b in report['blocks']],marker='o',label='Analyzer p95');axes[1].plot(labels,[b['p95_native_detect_ms'] for b in report['blocks']],marker='o',label='Detect-body p95');axes[1].set_ylabel('Wall duration(ms)');axes[1].legend(fontsize=8)
    for ax in axes:ax.tick_params(axis='x',rotation=15)
    fig.suptitle('CameraX input-format ABBA | no human precision or ten-minute credit');fig.tight_layout();plot=OUT/'format-camera-durations.png';assert not plot.exists();fig.savefig(plot,dpi=140);plt.close(fig)
    receipt.write_text(json.dumps(dict(kind='original-actual-camera-raw-MP33-and-preview-display-not-human-form-truth',records=records,images=[dict(path=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [inputs,display,plot]],new_human_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n');print('Four actual camera/raw pairs, preview screens and mode timing chart saved')


if __name__=='__main__':main()
