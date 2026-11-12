"""Original source frames and raw angles for the recovered return-branch comparison."""
import gzip
import hashlib
import json
from PIL import Image, ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA, FEATURES, decoded_frames
from render_autonomous_pose_audit import EDGES


def main():
    output=DATA/'multipass-audit';output.mkdir(exist_ok=True)
    name='subject_018_shoulder_good_front'
    with gzip.open(DATA/'autonomous-heavy-cache'/(name+'.json.gz'),'rt') as stream:video=json.load(stream)
    before=json.loads((DATA/'calibration-envelope-heavy-replay'/(name+'-left.json')).read_text())
    after=json.loads((DATA/'multipass-capped-heavy-standard'/(name+'-left.json')).read_text())
    previous=next(a for a in before['attempts'] if a['started_ms']==5133)
    current=next(a for a in after['attempts'] if a['started_ms']==5133)
    assert previous['verdict']=='UNASSESSABLE' and current['verdict']=='ACCEPTABLE'
    path=DATA/'videos-v3'/(name+'.mp4')
    assert hashlib.sha256(path.read_bytes()).hexdigest()==video['source_video_sha256']
    samples={f['timestamp_ms']:f for f in video['frames']}
    times=[min(samples,key=lambda t:abs(t-w)) for w in [5133,5400,5667,5800,6000,6200,6533,6800]]
    sheet=Image.new('RGB',(1280,900),'#151515');draw=ImageDraw.Draw(sheet)
    records=[];decoder=decoded_frames(path,66,None)
    try:
        for timestamp,pixels,_ in decoder:
            if timestamp not in times:continue
            index=times.index(timestamp);f=samples[timestamp]
            image=Image.fromarray(pixels);canvas=ImageDraw.Draw(image);width,height=image.size
            for a,b in EDGES if f['landmarks'] else []:
                p,q=f['landmarks'][a],f['landmarks'][b]
                color=(30,230,95) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                canvas.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
            image.thumbnail((310,365));x=index%4*320;y=index//4*450
            sheet.paste(image,(x+(320-image.width)//2,y+65))
            draw.text((x+3,y+4),'Heavy LEFT | original source observation',fill='white')
            draw.text((x+3,y+24),f'PTS {timestamp} ms',fill='yellow')
            records.append({'video':video['video'],'source_sha256':video['source_video_sha256'],
                'model_sha256':video['model_sha256'],'timestamp_ms':timestamp})
            if timestamp>=max(times):break
    finally:decoder.close()
    assert len(records)==8
    sheet.save(output/'original-source-frames.jpg')
    fig,axes=plt.subplots(2,1,figsize=(12,7),constrained_layout=True)
    selected=[f for f in video['frames'] if 5000<=f['timestamp_ms']<=7000]
    for feature in ['angle.left_shoulder','angle.left_elbow']:
        index=FEATURES.index(feature)
        axes[0].plot([f['timestamp_ms']/1000 for f in selected],
            [f['features'][index] if f['features'][index] is not None else float('nan') for f in selected],'.-',label=feature)
    axes[0].set_title('Original raw Heavy angles; not anatomical ground truth');axes[0].set_ylabel('Degrees');axes[0].legend()
    for data,label in [(before,'Previous engine'),(after,'Compatible path + return branch')]:
        trace=[f for f in data['trace'] if 5000<=f['timestamp_ms']<=7000]
        axes[1].plot([f['timestamp_ms']/1000 for f in trace],[f['progress'] if f['progress'] is not None else float('nan') for f in trace],'.-',label=label)
    axes[1].set_title('Same observed driver, same attempt interval; ambiguity 16.02% to 0%')
    axes[1].set_xlabel('Original source PTS (seconds)');axes[1].set_ylabel('Reference progress');axes[1].legend()
    fig.savefig(output/'return-branch-evidence.png',dpi=130);plt.close(fig)
    (output/'provenance.json').write_text(json.dumps({'kind':'original-source-sanity-check-not-human-rep-labels','frames':records,
        'previous_engine_attempt':previous,'current_engine_attempt':current,'new_human_labels':0},indent=2)+'\n')
    print('Rendered eight original source observations, unchanged driver trace and newly accepted interval')


if __name__=='__main__':main()
