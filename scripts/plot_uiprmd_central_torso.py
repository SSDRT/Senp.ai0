"""Review fixed original development episodes, raw markers and derived angles."""
import hashlib,json,zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from prepare_uiprmd_central_torso import OUT,verify_protocol
from uiprmd_vicon import INDEX,markers,measurement_features
from uiprmd_central_torso import central_torso_features


def main():
    spec,_=verify_protocol();receipt=OUT/'original-marker-review.json'
    if receipt.exists():raise FileExistsError('Preserve original marker review')
    originals=json.loads((DATA/'development-vicon/manifest.json').read_bytes());reviews=[];images=[]
    edges=[('LSHO','RSHO'),('LSHO','LELB'),('RSHO','RELB'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    for record_id in spec['review_recordings']:
        entry=next(r for r in originals['records'] if r['id']==record_id);record=json.loads((DATA/'development-vicon'/entry['file']).read_bytes())
        archive='Movements.zip' if record['correct'] else 'Incorrect Movements.zip'
        with zipfile.ZipFile(DATA/archive) as z:raw=read_development_member(z,record['source_member'],set(spec['subjects']))
        assert hashlib.sha256(raw).hexdigest()==record['source_sha256']
        array,_=parse_publisher_array(raw,'Vicon');points=markers(array);old=measurement_features(array)['angle.right_shoulder'];new=central_torso_features(array)['angle.right_arm_central_torso']
        episode=record['episodes'][0];start,end=episode['start_frame'],episode['end_frame'];finite=np.flatnonzero(np.isfinite(new[start:end+1]))
        peak=start+int(finite[np.argmax(new[start:end+1][finite])]) if len(finite) else start
        fig=plt.figure(figsize=(14,7));trace=fig.add_subplot(2,1,1);time=np.arange(start,end+1)*10
        trace.plot(time,old[start:end+1],label='Original ipsilateral surface-marker ABC');trace.plot(time,new[start:end+1],label='Derived arm/central-torso angle');trace.set_xlabel('Original acquisition time(ms)');trace.set_ylabel('Degrees');trace.legend(fontsize=8);trace.grid(alpha=.25)
        snaps=[]
        for index,frame in enumerate([start,peak,end]):
            ax=fig.add_subplot(2,3,4+index,projection='3d');p=points[frame];valid=np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
            for a,b in edges:
                ids=[INDEX[a],INDEX[b]]
                if valid[ids].all():ax.plot(*p[ids].T,color='steelblue')
            ax.scatter(*p[valid].T,s=4,color='grey');ax.set_title(f'Original frame{frame} / {frame*10}ms',fontsize=9);ax.set_xlabel('Xmm');ax.set_ylabel('Ymm');ax.set_zlabel('Zmm');ax.set_box_aspect([1,1,1]);ax.view_init(15,45)
            snaps.append(dict(frame=frame,timestamp_ms=frame*10,original_proxy_degrees=float(old[frame]) if np.isfinite(old[frame]) else None,central_torso_degrees=float(new[frame]) if np.isfinite(new[frame]) else None,original_points= p.tolist(),observed_marker_mask=valid.tolist()))
        fig.suptitle(f'{record_id}, original episode1 | Surface markers, no camera/anatomical/fault-onset truth',fontsize=11);fig.tight_layout();target=OUT/(record_id+'-original-review.png');assert not target.exists();fig.savefig(target,dpi=130);plt.close(fig)
        images.append(dict(path=target.name,sha256=hashlib.sha256(target.read_bytes()).hexdigest()));reviews.append(dict(recording=record_id,archive=archive,member=record['source_member'],sha256=record['source_sha256'],original_episode=episode,snapshots=snaps))
    receipt.write_text(json.dumps(dict(kind='original-development-position-markers-not-camera-or-anatomical-labels',images=images,records=reviews,new_human_labels=0),indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print('Three fixed original episode traces and nine timestamped marker frames saved')


if __name__=='__main__':main()
