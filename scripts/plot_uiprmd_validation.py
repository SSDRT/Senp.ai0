"""Visual QA of timestamped original validation markers; no reconstruction or fit."""
import hashlib
import json
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA, checksum
from inspect_uiprmd_development import parse_publisher_array
from prepare_uiprmd_validation import frozen_candidate, read_validation_member
from uiprmd_vicon import markers, INDEX, measurement_features


def main():
    frozen_candidate()
    output = DATA/'validation-vicon'
    replay = json.loads((DATA/'validation-replay.json').read_text())
    links = [('LSHO','RSHO'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),
             ('LSHO','LELB'),('LELB','LWRA'),('RSHO','RELB'),('RELB','RWRA'),
             ('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    reads, plots = [], []
    for subject in ['05','06','07']:
        for correct in [True,False]:
            fig, axes = plt.subplots(2,5,figsize=(19,8))
            rendered = []
            archive_name = 'Movements.zip' if correct else 'Incorrect Movements.zip'
            with zipfile.ZipFile(DATA/archive_name) as archive:
                for i,movement in enumerate(['m01','m07']):
                    row = next(r for r in replay['rows'] if (r['subject'],r['movement'],r['correct']) == (subject,movement,correct))
                    raw = read_validation_member(archive,row['source_member'])
                    digest = hashlib.sha256(raw).hexdigest()
                    if digest != row['source_sha256']:raise ValueError('Source changed')
                    reads.append({'archive':archive_name,'member':row['source_member'],'sha256':digest})
                    array,_ = parse_publisher_array(raw,'Vicon')
                    points = markers(array);features = measurement_features(array)
                    driver = features['angle.left_knee' if movement == 'm01' else 'angle.right_shoulder']
                    start,end = row['episodes'][0]['start_frame'],row['episodes'][0]['end_frame']
                    peak = start+int((np.nanargmin if movement == 'm01' else np.nanargmax)(driver[start:end+1]))
                    for col,(frame,plane) in enumerate([(start,(0,2)),(peak,(0,2)),(start,(1,2)),(peak,(1,2))]):
                        ax = axes[i,col];p = points[frame]
                        observed = np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
                        ax.scatter(p[observed,plane[0]],p[observed,plane[1]],s=8,c='grey')
                        for a,b in links:
                            if not observed[INDEX[a]] or not observed[INDEX[b]]:continue
                            pair = p[[INDEX[a],INDEX[b]]]
                            ax.plot(pair[:,plane[0]],pair[:,plane[1]],lw=2,c='tab:blue' if a.startswith('L') else 'tab:orange')
                        centre = np.median(p[observed,plane[0]])
                        ax.set_xlim(centre-900,centre+900);ax.set_ylim(0,2100);ax.set_aspect('equal')
                        ax.set_title(f'{movement} s{subject} {"correct" if correct else "incorrect"}\n{frame*10}ms '+
                                     ('XZ' if plane==(0,2) else 'YZ')+f' | {39-int(observed.sum())} missing')
                        ax.set_xlabel('mm');ax.set_ylabel('height mm')
                        rendered.append({'movement':movement,'timestamp_ms':frame*10,'plane':list(plane)})
                    ax = axes[i,4]
                    keys = ['angle.left_knee','angle.left_hip'] if movement == 'm01' else ['angle.right_shoulder','angle.right_elbow']
                    for key in keys:ax.plot(np.arange(len(array))/100,features[key],label=key.replace('angle.',''))
                    ax.axvspan(start/100,end/100,alpha=.15,color='green',label='publisher rep 1')
                    for attempt in row['attempts']:
                        ax.axvline(attempt['completed_ms']/1000,alpha=.25,color='red')
                    ax.set_xlabel('acquisition seconds');ax.set_ylabel('marker proxy degrees');ax.legend(fontsize=8)
            fig.suptitle('UI-PRMD validation: original Vicon39 markers, no repair; red lines = emitted attempts')
            fig.tight_layout()
            path = output/f'geometry-qa-s{subject}-{"correct" if correct else "incorrect"}.png'
            fig.savefig(path,dpi=110);plt.close(fig)
            plots.append({'file':path.name,'sha256':checksum(path,'sha256'),'rendered':rendered})
    (output/'geometry-qa.json').write_text(json.dumps({'schema':'uiprmd-validation-geometry-qa/1',
        'subjects_opened':['05','06','07'],'final_test_subjects_opened':False,'reads':reads,'plots':plots},indent=2)+'\n')
    print(json.dumps({'plots':len(plots),'reads':len(reads),'directory':str(output)}))


if __name__ == '__main__':main()
