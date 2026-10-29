"""Fixed original marker frames and actual comparator bands; no new labels."""
import hashlib,json,zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from analyze_uiprmd_frontal_audit import OUT,read_records
from fetch_uiprmd import DATA
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from uiprmd_vicon import INDEX,markers,measurement_features
from uiprmd_frontal_arm import frontal_arm_features


def main():
    spec,records=read_records();target=OUT/'original-frame-review-corrected.json'
    if target.exists():raise FileExistsError('Preserve original diagnostic review')
    images=[];reviews=[]
    edges=[('LSHO','RSHO'),('LSHO','LELB'),('RSHO','RELB'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    def finite(v):return float(v) if np.isfinite(v) else None
    with zipfile.ZipFile(DATA/'Movements.zip') as archive:
        for selection in spec['review_examples']:
            record=next(x for x in records if x['subject']==selection['subject'] and x['movement']==selection['movement'] and x['correct'])
            raw=read_development_member(archive,record['source_member'],set(spec['subjects']))
            assert hashlib.sha256(raw).hexdigest()==record['source_sha256']
            array,_=parse_publisher_array(raw,'Vicon');points=markers(array);features=measurement_features(array)
            if record['movement']=='m07':features['angle.right_shoulder']=frontal_arm_features(array)['angle.right_arm_frontal_projection']
            episode=next(e for e in record['episodes'] if e['episode']==selection['episode']);first,last=episode['start_frame'],episode['end_frame'];driver=features[record['driver_feature']]
            finite_indices=np.flatnonzero(np.isfinite(driver[first:last+1]));peak=first
            if len(finite_indices):
                at=np.argmin(driver[first:last+1][finite_indices]) if record['movement']=='m01' else np.argmax(driver[first:last+1][finite_indices]);peak=first+int(finite_indices[at])
            selected=[s for s in record['samples'] if episode['start_ms']<=s['timestamp_ms']<=episode['end_ms']]
            times=np.arange(first,last+1)*10;names=[record['driver_feature'],'angle.left_hip' if record['movement']=='m01' else 'angle.right_elbow']
            fig=plt.figure(figsize=(16,12),layout='constrained');grid=fig.add_gridspec(3,6,height_ratios=[1,.8,1.3])
            for i,name in enumerate(names):
                ax=fig.add_subplot(grid[0,i*3:(i+1)*3]);ax.plot(times,features[name][first:last+1],color='black',label='Original observed-marker derived value')
                rows=[(s,f) for s in selected for f in s['features'] if f['observed_feature']==name]
                ax.fill_between([s['timestamp_ms'] for s,_ in rows],[f['lower_degrees']-f['acceptable_degrees'] for _,f in rows],[f['upper_degrees']+f['acceptable_degrees'] for _,f in rows],color='tab:blue',alpha=.2,label='Actual active-attempt acceptable band')
                if name==record['driver_feature']:ax.axhline(record['ready_angle']+record['required_progress']*(record['far_angle']-record['ready_angle']),color='red',linestyle='--',label='Unchanged required reference-progress angle')
                ax.set_title(name);ax.set_ylabel('Degrees');ax.set_xlabel('Original acquisition ms');ax.grid(alpha=.2);ax.legend(fontsize=7)
            ax=fig.add_subplot(grid[1,:]);frame_rows=[x for x in record['frames'] if episode['start_ms']<=x['timestamp_ms']<=episode['end_ms']]
            ax.plot([x['timestamp_ms'] for x in frame_rows],[x['progress'] if x['progress'] is not None else np.nan for x in frame_rows],label='Actual causal output progress');ax.axhline(record['ready_progress'],color='grey',linestyle='--',label='Ready boundary');ax.axhline(record['required_progress'],color='red',linestyle='--',label='Required boundary');ax.set_xlabel('Original acquisition ms');ax.set_ylabel('Reference-relative progress');ax.legend(fontsize=8);ax.grid(alpha=.2)
            snapshots=[]
            for i,index in enumerate([first,peak,last]):
                ax=fig.add_subplot(grid[2,i*2:(i+1)*2],projection='3d');p=points[index];present=np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
                for a,b in edges:
                    ids=[INDEX[a],INDEX[b]]
                    if present[ids].all():ax.plot(*p[ids].T,color='steelblue')
                ax.scatter(*p[present].T,s=4,color='grey');ax.set_xlabel('Xmm');ax.set_ylabel('Ymm');ax.set_zlabel('Zmm');ax.view_init(15,45);ax.set_box_aspect([1,1,1]);ax.set_title(f'Original frame{index} / {index*10}ms',fontsize=9)
                snapshots.append(dict(frame=index,timestamp_ms=index*10,driver_degrees=finite(driver[index]),original_points=p.tolist(),observed_marker_mask=present.tolist()))
            name=f"{record['movement']}-s{record['subject']}-episode{selection['episode']}-diagnostic-reviewed.png";path=OUT/name;assert not path.exists();fig.suptitle(f"Publisher-correct {record['movement']} s{record['subject']} episode{selection['episode']} | Reference s{record['reference_subject']} | No new fault labels",fontsize=11);fig.savefig(path,dpi=120);plt.close(fig)
            images.append(dict(path=name,sha256=hashlib.sha256(path.read_bytes()).hexdigest()));reviews.append(dict(subject=record['subject'],movement=record['movement'],episode=episode,source_member=record['source_member'],source_sha256=record['source_sha256'],snapshots=snapshots))
    target.write_text(json.dumps(dict(kind='original-marker-and-actual-band-diagnostic-not-human-camera-truth',records=reviews,images=images,new_annotations=0),indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print('Three fixed actual-band traces and nine original marker snapshots preserved')


if __name__=='__main__':main()
