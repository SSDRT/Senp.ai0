"""Timestamped real-video overlays and causal traces. Engine verdicts are not expert truth."""
import gzip
import json
from pathlib import Path
from PIL import Image,ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA,decoded_frames

CASES=['subject_001_push_up_good_side-left','subject_001_shoulder_good_front-left',
       'subject_001_shoulder_bad_front-left','subject_005_squat_good_front-left']
EDGES=[(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28),(27,31),(28,32)]

def main():
    output=DATA/'qualified-rep-audit';output.mkdir(exist_ok=True)
    sheet=Image.new('RGB',(1280,1800),'#121212');draw=ImageDraw.Draw(sheet)
    fig,axes=plt.subplots(4,1,figsize=(14,12),constrained_layout=True)
    records=[]
    for row,name in enumerate(CASES):
        report=json.loads((DATA/'qualified-video-replay'/(name+'.json')).read_text())
        times=[]
        if report['faults']:times.append(report['faults'][0]['confirmed_ms'])
        times += [a['completed_ms'] for a in report['attempts'] if a['verdict'] in ['CORRECT','ACCEPTABLE']][:1]
        times += [a['completed_ms'] for a in report['attempts'] if a['verdict']=='UNASSESSABLE'][:1]
        times += [6000,10000,16000,20000]
        selected=[]
        for requested in times:
            f=min(report['trace'],key=lambda r:abs(r['timestamp_ms']-requested))
            if f['timestamp_ms'] not in selected:selected.append(f['timestamp_ms'])
            if len(selected)==4:break
        with gzip.open(DATA/'rep-world-feature-cache'/(report['video'].removesuffix('.mp4')+'.json.gz'),'rt') as stream:cache=json.load(stream)
        frames={f['timestamp_ms']:f for f in cache['frames']};trace={f['timestamp_ms']:f for f in report['trace']}
        decoder=decoded_frames(DATA/'videos-v3'/report['video'],66)
        try:
            for timestamp,pixels,_ in decoder:
                if timestamp not in selected:continue
                f=frames[timestamp];image=Image.fromarray(pixels);d=ImageDraw.Draw(image);width,height=image.size
                for a,b in EDGES if f['landmarks'] else []:
                    p,q=f['landmarks'][a],f['landmarks'][b]
                    color=(40,240,90) if min(p[3],p[4],q[3],q[4])>=.65 else (130,130,130)
                    d.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=3)
                image.thumbnail((310,360));col=selected.index(timestamp);x=col*320
                sheet.paste(image,(x+(320-image.width)//2,row*450+70))
                draw.text((x+5,row*450+5),name.replace('subject_',''),fill='white')
                t=trace[timestamp]
                draw.text((x+5,row*450+22),f"PTS {timestamp}ms | {t['stage']}",fill='#ffff80')
                draw.text((x+5,row*450+39),f"count {t['qualified_count']} | disqualified {t['active_disqualified']}",fill='white')
                records.append({'video':report['video'],'side':report['side'],'timestamp_ms':timestamp,'engine_state':t['stage'],'engine_qualified_count':t['qualified_count']})
                if timestamp>=max(selected):break
        finally:decoder.close()
        ax=axes[row];xs=[f['timestamp_ms']/1000 for f in report['trace']]
        ax.plot(xs,[f['progress'] for f in report['trace']],label='observed driver progress',color='#2255aa')
        ax.axhline(.85,color='green',ls='--',label='required endpoint (engineering default)')
        ax.axhline(.65,color='orange',ls=':',label='clear partial-range threshold')
        colors={'CORRECT':'#50aa50','ACCEPTABLE':'#8fcf8f','REJECTED':'#d05a5a','UNASSESSABLE':'#aaaaaa'}
        for a in report['attempts']:ax.axvspan(a['started_ms']/1000,a['completed_ms']/1000,color=colors[a['verdict']],alpha=.18)
        for f in report['faults']:ax.axvline(f['confirmed_ms']/1000,color='red',alpha=.5)
        ax.set_title(name);ax.set_ylabel('reference-relative progress');ax.set_xlabel('actual source PTS (seconds)');ax.set_ylim(-.5,1.6)
    axes[0].legend(loc='upper right',fontsize=8)
    fig.suptitle('Development diagnostics: shaded engine outcomes are not independent form labels')
    fig.savefig(output/'causal-rep-traces.png',dpi=130);plt.close(fig)
    sheet.save(output/'timestamped-pose-contact-sheet.jpg')
    (output/'frame-provenance.json').write_text(json.dumps({'kind':'source-PTS-engine-diagnostics-not-expert-accuracy','frames':records},indent=2)+'\n')
    print('Inspected-candidate source frames',len(records),'output',output)

if __name__=='__main__':main()
