"""Inspect repeated-range recovery, extra reference footage, and a full-reference failure."""
import gzip
import hashlib
import json
from PIL import Image,ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA,FEATURES,decoded_frames
from render_autonomous_pose_audit import EDGES


def main():
    output=DATA/'calibration-recovery-audit';output.mkdir(exist_ok=True)
    cases=[('subject_001_shoulder_good_front','heavy','shoulder',[15667,16000,16067,16133]),
           ('subject_001_bicep_curl_good_front','full','elbow',[23867,26000,28000,30000]),
           ('subject_001_push_up_good_side','full','elbow',[22600,24200,26000,28000]),
           ('subject_005_shoulder_good_front','full','shoulder',[23000,25000,28000,30000])]
    sheet=Image.new('RGB',(1280,1800),'#151515');draw=ImageDraw.Draw(sheet);records=[]
    fig,axes=plt.subplots(4,1,figsize=(13,12),constrained_layout=True)
    for row,(name,model,driver,wanted) in enumerate(cases):
        with gzip.open(DATA/('complete-reference-'+model+'-cache')/(name+'.json.gz'),'rt') as stream:video=json.load(stream)
        path=DATA/'videos-v3'/(name+'.mp4')
        assert hashlib.sha256(path.read_bytes()).hexdigest()==video['source_video_sha256']
        samples={f['timestamp_ms']:f for f in video['frames']}
        times=[min(samples,key=lambda t:abs(t-w)) for w in wanted]
        decoder=decoded_frames(path,66,None)
        index=FEATURES.index('angle.left_'+driver)
        try:
            for timestamp,pixels,_ in decoder:
                if timestamp not in times:continue
                observed=samples[timestamp];col=times.index(timestamp)
                image=Image.fromarray(pixels);canvas=ImageDraw.Draw(image);width,height=image.size
                for a,b in EDGES if observed['landmarks'] else []:
                    p,q=observed['landmarks'][a],observed['landmarks'][b]
                    color=(30,230,95) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                    canvas.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
                image.thumbnail((310,365));x=col*320;y=row*450
                sheet.paste(image,(x+(320-image.width)//2,y+70))
                draw.text((x+3,y+3),name.replace('subject_',''),fill='white')
                draw.text((x+3,y+21),f'{model} LEFT | original PTS {timestamp} ms',fill='yellow')
                value=observed['features'][index]
                draw.text((x+3,y+39),f'raw driver {value:.2f} deg' if value is not None else 'driver unavailable',fill='white')
                records.append({'video':video['video'],'source_sha256':video['source_video_sha256'],'model_sha256':video['model_sha256'],
                    'timestamp_ms':timestamp,'model':model,'observed_side':'LEFT'})
                if timestamp>=max(times):break
        finally:decoder.close()
        selected=[f for f in video['frames'] if row!=0 or 15300<=f['timestamp_ms']<=16400]
        axis=axes[row]
        axis.plot([f['timestamp_ms']/1000 for f in selected],
            [f['features'][index] if f['features'][index] is not None else float('nan') for f in selected],'.-',markersize=3,label='Raw observed driver; not anatomical truth')
        if row==0:
            axis.axhline(81.36540638201268-12,color='orange',linestyle='--',label='Previous lower range edge minus unchanged 12 deg allowance')
            axis.axhline(79.89328699615119-12,color='green',linestyle='--',label='Repeated-cycle lower edge minus same allowance')
        else:
            axis.axvline(24,color='red',linestyle='--',label='Previous research extraction cap')
            axis.axvspan(24,video['source_duration_ms']/1000,color='green',alpha=.1,label='Additional original reference footage')
        axis.set_title(name+' | '+model+' LEFT');axis.set_xlabel('Original source PTS (seconds)');axis.set_ylabel('Observed driver angle (degrees)');axis.legend(fontsize=7)
    sheet.save(output/'original-reference-frames.jpg');fig.savefig(output/'reference-evidence.png',dpi=125);plt.close(fig)
    (output/'provenance.json').write_text(json.dumps({'kind':'original-reference-observation-diagnostics-not-human-form-labels','frames':records,
        'includes_failure':'The complete subject_005_shoulder_good_front LEFT reference remains withheld, despite being usable under the older truncated input.'},indent=2)+'\n')
    print('Rendered',len(records),'original reference observations, including evidence after 24 seconds and an unavailable reference')


if __name__=='__main__':main()
