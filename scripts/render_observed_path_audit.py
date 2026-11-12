"""Inspect original frames around the retained pre-departure evidence; these are not form labels."""
import gzip
import json
from PIL import Image,ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA,decoded_frames
from render_autonomous_pose_audit import EDGES


def main():
    output=DATA/'observed-path-audit';output.mkdir(exist_ok=True)
    name='subject_001_shoulder_good_front'
    timestamps=[5000,5200,5267,5333]
    videos={}
    for model,directory in [('Full','rep-world-feature-cache'),('Heavy','autonomous-heavy-cache')]:
        with gzip.open(DATA/directory/(name+'.json.gz'),'rt') as stream:videos[model]=json.load(stream)
    assert videos['Full']['source_video_sha256']==videos['Heavy']['source_video_sha256']
    rows={model:{f['timestamp_ms']:f for f in video['frames']} for model,video in videos.items()}
    trace=json.loads((DATA/'observed-path-final-full-replay'/(name+'-left.json')).read_text())
    by_pts={f['timestamp_ms']:f for f in trace['trace']}
    sheet=Image.new('RGB',(1280,900),'#151515');draw=ImageDraw.Draw(sheet);evidence=[]
    decoder=decoded_frames(DATA/'videos-v3'/(name+'.mp4'),66)
    try:
        for timestamp,pixels,_ in decoder:
            if timestamp not in timestamps:continue
            col=timestamps.index(timestamp)
            for row,(model,video) in enumerate(videos.items()):
                observed=rows[model][timestamp]
                image=Image.fromarray(pixels);canvas=ImageDraw.Draw(image);width,height=image.size
                for a,b in EDGES if observed['landmarks'] else []:
                    p,q=observed['landmarks'][a],observed['landmarks'][b]
                    color=(30,230,95) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                    canvas.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
                image.thumbnail((310,365));x=col*320;y=row*450
                sheet.paste(image,(x+(320-image.width)//2,y+70))
                draw.text((x+3,y+3),f'{model} | original PTS {timestamp} ms',fill='yellow')
                draw.text((x+3,y+21),name.replace('subject_',''),fill='white')
                if model=='Full':draw.text((x+3,y+39),f'engine progress {by_pts[timestamp]["progress"]:.3f}',fill='white')
                evidence.append({'video':video['video'],'source_video_sha256':video['source_video_sha256'],
                    'model':model,'model_sha256':video['model_sha256'],'timestamp_ms':timestamp})
            if timestamp>=max(timestamps):break
    finally:decoder.close()
    sheet.save(output/'early-motion-original-frames.jpg')
    selected=[f for f in trace['trace'] if 4900<=f['timestamp_ms']<=6900]
    fig,axis=plt.subplots(figsize=(12,4.5),constrained_layout=True)
    axis.plot([f['timestamp_ms']/1000 for f in selected],[f['progress'] if f['progress'] is not None else float('nan') for f in selected],'.-',label='Raw Full driver progress')
    axis.axhline(.1,color='grey',linestyle='--',label='Ready threshold .10')
    axis.axhline(.2,color='purple',linestyle='--',label='Attempt departure .20')
    axis.axhline(.85,color='green',linestyle='--',label='Far milestone .85')
    axis.scatter([5.2,5.267],[by_pts[t]['progress'] for t in [5200,5267]],color='red',s=70,zorder=5,label='Retained visible early movement')
    axis.axvline(5.333,color='purple',alpha=.5,label='Attempt starts')
    axis.set_title('Original source evidence: early visible motion must survive the attempt-start threshold')
    axis.set_xlabel('Original source PTS (seconds)');axis.set_ylabel('Reference-normalized driver progress; not form accuracy');axis.legend(loc='lower right')
    fig.savefig(output/'early-motion-progress.png',dpi=130);plt.close(fig)
    (output/'provenance.json').write_text(json.dumps({'kind':'original-source-measurement-diagnostics-not-form-ground-truth','frames':evidence,
        'interpretation':'The final policy retains pre-departure observations at 5200/5267 ms. It does not label the human performance from model confidence or progress.'},indent=2)+'\n')
    print('Rendered',len(evidence),'model/source observations at original PTS')


if __name__=='__main__':main()
