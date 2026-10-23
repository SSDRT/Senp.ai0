"""Render same-PTS Full/Heavy evidence. Agreement and engine counts are not form labels."""
import gzip
import json
from PIL import Image,ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA,FEATURES,decoded_frames

CASES=['subject_001_push_up_good_side','subject_005_squat_good_front','subject_018_squat_good_front','subject_001_bicep_curl_good_side']
DRIVERS=['elbow','knee','knee','elbow']
EDGES=[(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28),(27,31),(28,32)]

def main():
    output=DATA/'autonomous-pose-audit';output.mkdir(exist_ok=True)
    sheet=Image.new('RGB',(1280,1800),'#151515');draw=ImageDraw.Draw(sheet);records=[]
    fig,axes=plt.subplots(4,1,figsize=(14,11),constrained_layout=True)
    for row,(name,driver) in enumerate(zip(CASES,DRIVERS)):
        videos={}
        for model,directory in [('Full','rep-world-feature-cache'),('Heavy','autonomous-heavy-cache')]:
            with gzip.open(DATA/directory/(name+'.json.gz'),'rt') as stream:videos[model]=json.load(stream)
        timestamps=[min(videos['Full']['frames'],key=lambda f:abs(f['timestamp_ms']-t))['timestamp_ms'] for t in [6000,10000]]
        cache={model:{f['timestamp_ms']:f for f in video['frames']} for model,video in videos.items()}
        decoder=decoded_frames(DATA/'videos-v3'/(name+'.mp4'),66)
        try:
            for timestamp,pixels,_ in decoder:
                if timestamp not in timestamps:continue
                for index,(model,video) in enumerate(videos.items()):
                    col=timestamps.index(timestamp)*2+index;f=cache[model][timestamp]
                    image=Image.fromarray(pixels);d=ImageDraw.Draw(image);width,height=image.size
                    for a,b in EDGES if f['landmarks'] else []:
                        p,q=f['landmarks'][a],f['landmarks'][b]
                        color=(30,230,95) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                        d.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
                    image.thumbnail((310,365));x=col*320;y=row*450
                    sheet.paste(image,(x+(320-image.width)//2,y+70));draw.text((x+3,y+3),name.replace('subject_',''),fill='white')
                    draw.text((x+3,y+20),f'{model} | original PTS {timestamp}ms',fill='yellow')
                    value=f['features'][FEATURES.index('angle.left_'+driver)]
                    draw.text((x+3,y+37),f'observed driver {value:.1f} deg' if value is not None else 'driver evidence unavailable',fill='white')
                    records.append({'video':video['video'],'source_sha256':video['source_video_sha256'],'model':model,
                                    'model_sha256':video['model_sha256'],'timestamp_ms':timestamp})
                if timestamp>=max(timestamps):break
        finally:decoder.close()
        for model,video in videos.items():
            index=FEATURES.index('angle.left_'+driver)
            axes[row].plot([f['timestamp_ms']/1000 for f in video['frames']],
                [f['features'][index] if f['features'][index] is not None else float('nan') for f in video['frames']],label=model,alpha=.8)
        axes[row].set_title(name);axes[row].set_ylabel('observed angle degrees');axes[row].set_xlabel('original source PTS seconds');axes[row].legend()
    fig.suptitle('Same-source model comparison: model outputs are not independent geometry/form truth')
    fig.savefig(output/'full-heavy-angle-traces.png',dpi=120);plt.close(fig)
    sheet.save(output/'full-heavy-same-pts-contact-sheet.jpg')
    (output/'provenance.json').write_text(json.dumps({'kind':'same-PTS-model-output-diagnostics-not-form-truth','frames':records},indent=2)+'\n')
    print('Rendered',len(records),'model/source observations at matched original PTS')

if __name__=='__main__':main()
