"""Original-PTS overlays and before/after phase diagnostics; no human form labels."""
import gzip
import hashlib
import json
from PIL import Image,ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA,decoded_frames
from render_autonomous_pose_audit import EDGES


def main():
    output=DATA/'departure-peak-audit';output.mkdir(exist_ok=True)
    cases=[('subject_005_squat_good_front','left','full',20000,22000,[20600,20733,21067,21400]),
           ('subject_005_bicep_curl_good_front','right','full',21800,23933,[22000,22533,22667,23467]),
           ('subject_005_squat_bad_front','left','heavy',15533,16400,[15667,15733,16000,16267]),
           ('subject_001_shoulder_good_front','left','full',5100,6800,[5267,5333,5400,5467])]
    sheet=Image.new('RGB',(1280,1800),'#151515');draw=ImageDraw.Draw(sheet);records=[]
    fig,axes=plt.subplots(4,1,figsize=(13,12),constrained_layout=True)
    for row,(name,side,model,start,end,wanted) in enumerate(cases):
        cache='rep-world-feature-cache' if model=='full' else 'autonomous-heavy-cache'
        with gzip.open(DATA/cache/(name+'.json.gz'),'rt') as stream:video=json.load(stream)
        assert hashlib.sha256((DATA/'videos-v3'/(name+'.mp4')).read_bytes()).hexdigest()==video['source_video_sha256']
        samples={f['timestamp_ms']:f for f in video['frames']}
        times=[min(samples,key=lambda t:abs(t-w)) for w in wanted]
        old=json.loads((DATA/('observed-path-final-'+model+'-replay')/(name+'-'+side+'.json')).read_text())
        new=json.loads((DATA/('departure-peak-'+model+'-replay')/(name+'-'+side+'.json')).read_text())
        by_pts={f['timestamp_ms']:f for f in new['trace']}
        decoder=decoded_frames(DATA/'videos-v3'/(name+'.mp4'),66)
        try:
            for time,pixels,_ in decoder:
                if time not in times:continue
                col=times.index(time);observed=samples[time]
                image=Image.fromarray(pixels);canvas=ImageDraw.Draw(image);width,height=image.size
                for a,b in EDGES if observed['landmarks'] else []:
                    p,q=observed['landmarks'][a],observed['landmarks'][b]
                    color=(30,230,95) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                    canvas.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
                image.thumbnail((310,365));x=col*320;y=row*450
                sheet.paste(image,(x+(320-image.width)//2,y+75))
                draw.text((x+3,y+3),name.replace('subject_',''),fill='white')
                draw.text((x+3,y+21),f'{model} {side} | original PTS {time} ms',fill='yellow')
                p=by_pts[time]['progress']
                draw.text((x+3,y+39),f'engine p={p:.3f}' if p is not None else 'engine evidence unavailable',fill='white')
                draw.text((x+3,y+55),by_pts[time]['stage'],fill='white')
                records.append({'video':video['video'],'source_sha256':video['source_video_sha256'],'model_sha256':video['model_sha256'],
                                'model':model,'side':side,'timestamp_ms':time})
                if time>=max(times):break
        finally:decoder.close()
        trace=[f for f in new['trace'] if start<=f['timestamp_ms']<=end]
        axis=axes[row]
        axis.plot([f['timestamp_ms']/1000 for f in trace],[f['progress'] if f['progress'] is not None else float('nan') for f in trace],'.-',label='Unchanged raw normalized progress')
        for report,level,color,label in [(old,-.55,'orange','Previous return phase'),(new,-.7,'blue','Corrected return phase')]:
            selected=[f for f in report['trace'] if start<=f['timestamp_ms']<=end and f['stage']=='RETURNING']
            axis.scatter([f['timestamp_ms']/1000 for f in selected],[level]*len(selected),marker='|',color=color,label=label)
        axis.axhline(.2,color='grey',linestyle='--',label='Departure .20');axis.axhline(.85,color='green',linestyle='--',label='Far milestone .85')
        axis.set_title(f'{name} | {model} {side}');axis.set_xlabel('Original source PTS (seconds)')
        axis.set_ylabel('Reference-normalized progress');axis.legend(loc='upper right',fontsize=7)
    sheet.save(output/'original-frames.jpg');fig.savefig(output/'phase-comparison.png',dpi=130);plt.close(fig)
    (output/'provenance.json').write_text(json.dumps({'kind':'source-observation-and-phase-diagnostics-not-expert-truth','frames':records},indent=2)+'\n')
    print('Rendered',len(records),'original source/model observations; no pose or human labels modified')


if __name__=='__main__':main()
