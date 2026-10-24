"""Visual QA of original development Vicon markers and derived angle proxies."""
import json
import argparse
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from uiprmd_vicon import INDEX, markers, measurement_features


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject',choices=['01','02','03','04'],default='01')
    parser.add_argument('--incorrect',action='store_true')
    args=parser.parse_args()
    links = [('LSHO','RSHO'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),
             ('LSHO','LELB'),('LELB','LWRA'),('RSHO','RELB'),('RELB','RWRA'),
             ('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    fig, axes = plt.subplots(2,5,figsize=(19,8))
    archive_name='Incorrect Movements.zip' if args.incorrect else 'Movements.zip'
    directory='Incorrect Movements' if args.incorrect else 'Movements'
    suffix='_inc' if args.incorrect else ''
    quality='incorrect' if args.incorrect else 'correct'
    with zipfile.ZipFile(DATA/archive_name) as archive:
        for row,movement in enumerate(['m01','m07']):
            raw = read_development_member(archive,f'{directory}/Vicon/Positions/{movement}_s{args.subject}_positions{suffix}.txt',{'01','02','03','04'})
            array,_ = parse_publisher_array(raw,'Vicon')
            points = markers(array); features = measurement_features(array)
            driver = features['angle.left_knee' if movement=='m01' else 'angle.right_shoulder']
            record = json.loads((DATA/f'development-vicon/{movement}-s{args.subject}-{quality}.json').read_text())
            first = record['episodes'][0]
            start,end = first['start_frame'],first['end_frame']
            peak = start+int((np.nanargmin if movement=='m01' else np.nanargmax)(driver[start:end+1]))
            for col,(frame,plane) in enumerate([(start,(0,2)),(peak,(0,2)),(start,(1,2)),(peak,(1,2))]):
                ax=axes[row,col];p=points[frame]
                observed=np.isfinite(p).all(axis=1) & (np.linalg.norm(p,axis=1)>0)
                ax.scatter(p[observed,plane[0]],p[observed,plane[1]],s=8,c='grey')
                for a,b in links:
                    if not observed[INDEX[a]] or not observed[INDEX[b]]:continue
                    pair=p[[INDEX[a],INDEX[b]]]
                    ax.plot(pair[:,plane[0]],pair[:,plane[1]],lw=2,c='tab:blue' if a.startswith('L') else 'tab:orange')
                ax.set_aspect('equal')
                centre=np.nanmedian(points[:,:,plane[0]][np.linalg.norm(points,axis=2)>0])
                ax.set_xlim(centre-900,centre+900);ax.set_ylim(0,2100)
                ax.set_title(f'{movement} s{args.subject} {quality}\n{frame*10}ms '+('XZ' if plane==(0,2) else 'YZ')+f' | {39-int(observed.sum())} missing')
                ax.set_xlabel('mm');ax.set_ylabel('height mm')
            ax=axes[row,4]
            for key in (['angle.left_knee','angle.left_hip'] if movement=='m01' else ['angle.right_shoulder','angle.right_elbow']):
                ax.plot(np.arange(len(array))/100,features[key],label=key.replace('angle.',''))
            ax.axvspan(start/100,end/100,alpha=.15,color='green',label='original rep 1')
            ax.set_xlabel('original acquisition seconds');ax.set_ylabel('marker proxy angle / degrees');ax.legend(fontsize=8)
    fig.suptitle('UI-PRMD development only: original Vicon39 global markers; no repair or MP33 reconstruction')
    fig.tight_layout();path=DATA/f'development-vicon/geometry-qa-s{args.subject}-{quality}.png';fig.savefig(path,dpi=130);plt.close(fig)
    print(path)


if __name__=='__main__':main()
