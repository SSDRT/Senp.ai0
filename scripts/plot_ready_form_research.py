"""Original marker views of known-correct validation examples; no new labels or pose repair."""
import hashlib
import json
import zipfile
from datetime import datetime,timezone
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA,ROOT
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from prepare_uiprmd_validation import read_validation_member
from uiprmd_vicon import markers,measurement_features,INDEX


def main(prefix='ready-form'):
    if prefix not in {'ready-form','clear-ready'}:raise ValueError('Unknown frozen research checkpoint')
    from evaluate_uiprmd_development import evaluate_rows
    replay=json.loads((DATA/f'{prefix}-validation-replay.json').read_text())
    verdicts={(r['subject'],r['movement'],r['correct'],r['episode']):r['verdict'] for r in evaluate_rows(replay['rows'])['rep_results']}
    path=DATA/(prefix+'-original-markers.png');receipt=DATA/(prefix+'-original-markers.json')
    if path.exists() or receipt.exists():raise FileExistsError('Preserve marker QA')
    cases=[('01',3,'development'),('06',3,'validation'),('06',5,'validation')]
    links=[('LSHO','RSHO'),('LASI','RASI'),('LSHO','LASI'),('RSHO','RASI'),('LASI','LKNE'),('LKNE','LANK'),
           ('RASI','RKNE'),('RKNE','RANK'),('LSHO','LELB'),('LELB','LWRA'),('RSHO','RELB'),('RELB','RWRA')]
    fig,axes=plt.subplots(3,4,figsize=(17,11));reads=[];rendered=[]
    with zipfile.ZipFile(DATA/'Movements.zip') as archive:
        for row,(subject,number,scope) in enumerate(cases):
            record=json.loads((DATA/f'{scope}-vicon/m01-s{subject}-correct.json').read_text())
            intent=dict(subject=subject,member=record['source_member'],utc=datetime.now(timezone.utc).isoformat())
            with (DATA/(prefix+'-marker-access.jsonl')).open('a') as access:
                access.write(json.dumps(intent|dict(status='requested'))+'\n');access.flush()
                raw=(read_development_member(archive,record['source_member'],{'01','02','03','04'}) if scope=='development' else
                     read_validation_member(archive,record['source_member']))
                digest=hashlib.sha256(raw).hexdigest()
                if digest!=record['source_sha256']:raise ValueError('Original marker file changed')
                reads.append(intent|dict(sha256=digest));access.write(json.dumps(reads[-1]|dict(status='read'))+'\n')
            array,_=parse_publisher_array(raw,'Vicon');points=markers(array);features=measurement_features(array)
            episode=next(e for e in record['episodes'] if e['episode']==number)
            start,end=episode['start_frame'],episode['end_frame']
            peak=start+int(np.nanargmin(features['angle.left_knee'][start:end+1]))
            for col,(frame,plane) in enumerate([(start,(0,2)),(peak,(0,2)),(peak,(1,2))]):
                p=points[frame];valid=np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
                ax=axes[row,col];ax.scatter(p[valid,plane[0]],p[valid,plane[1]],s=8,color='grey')
                for a,b in links:
                    if valid[INDEX[a]] and valid[INDEX[b]]:
                        pair=p[[INDEX[a],INDEX[b]]];ax.plot(pair[:,plane[0]],pair[:,plane[1]],color='tab:blue' if a.startswith('L') else 'tab:orange')
                center=np.median(p[valid,plane[0]]);ax.set_xlim(center-900,center+900);ax.set_ylim(0,2400);ax.set_aspect('equal')
                ax.set_title(f's{subject} correct rep {number} | {frame*10}ms\n'+('XZ' if plane==(0,2) else 'YZ')+f' | {39-int(valid.sum())} missing')
                ax.set_xlabel('mm');ax.set_ylabel('height mm')
                rendered.append(dict(subject=subject,episode=number,original_timestamp_ms=frame*10,plane=list(plane),missing_markers=39-int(valid.sum())))
            ax=axes[row,3]
            for name in ['angle.left_knee','angle.left_hip']:
                ax.plot(np.arange(start,end+1)/100,features[name][start:end+1],label=name)
            ax.set_title('Reference context' if subject=='01' else 'Baseline ACCEPTABLE → candidate '+verdicts[(subject,'m01',True,number)])
            ax.set_xlabel('original acquisition seconds');ax.set_ylabel('surface-marker angle, degrees');ax.legend(fontsize=8)
    fig.suptitle(prefix+': known-correct validation examples; original Vicon39, not MP33 camera accuracy; no repair or relabelling')
    fig.tight_layout();fig.savefig(path,dpi=110);plt.close(fig)
    receipt.write_text(json.dumps(dict(schema='ready-form-original-marker-qa/1',source_reads=reads,rendered=rendered,
        plot_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),new_labels=0,final_test_access=False),indent=2)+'\n',newline='\n')
    print(path)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--prefix',choices=['ready-form','clear-ready'],default='ready-form')
    main(parser.parse_args().prefix)
