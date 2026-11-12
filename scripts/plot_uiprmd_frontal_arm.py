"""Original development marker QA; no RGB, anatomical or fault-onset labels."""
import hashlib,json,zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from prepare_uiprmd_frontal_arm import OUT,verify_protocol
from uiprmd_vicon import INDEX,markers,measurement_features
from uiprmd_central_torso import central_torso_features
from uiprmd_frontal_arm import frontal_arm_features,MINIMUM_PROJECTED_FRACTION


def main():
    spec,_=verify_protocol();receipt=OUT/'original-marker-review.json'
    if receipt.exists():raise FileExistsError('Preserve original frame review')
    manifest=json.loads((DATA/'development-vicon/manifest.json').read_bytes());reviews=[];images=[]
    edges=[('LSHO','RSHO'),('LSHO','LELB'),('RSHO','RELB'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    def finite(value):return float(value) if np.isfinite(value) else None
    for identifier in spec['review_recordings']:
        entry=next(e for e in manifest['records'] if e['id']==identifier);record=json.loads((DATA/'development-vicon'/entry['file']).read_bytes())
        archive='Movements.zip' if record['correct'] else 'Incorrect Movements.zip'
        with zipfile.ZipFile(DATA/archive) as z:raw=read_development_member(z,record['source_member'],set(spec['subjects']))
        assert hashlib.sha256(raw).hexdigest()==record['source_sha256']
        array,_=parse_publisher_array(raw,'Vicon');points=markers(array)
        abc=measurement_features(array)['angle.right_shoulder'];central=central_torso_features(array)['angle.right_arm_central_torso'];features=frontal_arm_features(array)
        projection=features['angle.right_arm_frontal_projection'];normal=features['angle.right_arm_out_of_frontal_plane'];fraction=features['relation.right_arm_projected_fraction']
        episode=record['episodes'][0];start,end=episode['start_frame'],episode['end_frame'];observed=np.flatnonzero(np.isfinite(projection[start:end+1]));peak=start+int(observed[np.argmax(projection[start:end+1][observed])]) if len(observed) else start
        fig=plt.figure(figsize=(15,10));time=np.arange(start,end+1)*10
        top=fig.add_subplot(3,1,1)
        for values,name in [(abc,'Original surface-marker ABC'),(central,'Prior central-torso angle'),(projection,'Unsigned frontal projection'),(normal,'Retained out-of-plane component')]:top.plot(time,values[start:end+1],label=name)
        top.set_ylabel('Degrees');top.legend(fontsize=8);top.grid(alpha=.25)
        middle=fig.add_subplot(3,1,2);middle.plot(time,fraction[start:end+1],label='Observed projected-length / arm-length');middle.axhline(MINIMUM_PROJECTED_FRACTION,color='red',linestyle='--',label='Frozen conditioning gate');middle.set_xlabel('Original acquisition time(ms)');middle.set_ylim(0,1.05);middle.legend(fontsize=8);middle.grid(alpha=.25)
        snaps=[]
        for i,frame in enumerate([start,peak,end]):
            ax=fig.add_subplot(3,3,7+i,projection='3d');p=points[frame];valid=np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
            for a,b in edges:
                ids=[INDEX[a],INDEX[b]]
                if valid[ids].all():ax.plot(*p[ids].T,color='steelblue')
            ax.scatter(*p[valid].T,s=4,color='grey');ax.set_title(f'Original frame{frame} / {frame*10}ms',fontsize=9);ax.set_xlabel('Xmm');ax.set_ylabel('Ymm');ax.set_zlabel('Zmm');ax.set_box_aspect([1,1,1]);ax.view_init(15,45)
            snaps.append(dict(frame=frame,timestamp_ms=frame*10,original_proxy_degrees=finite(abc[frame]),central_torso_degrees=finite(central[frame]),frontal_projection_degrees=finite(projection[frame]),out_of_plane_degrees=finite(normal[frame]),projected_fraction=finite(fraction[frame]),original_points=p.tolist(),observed_marker_mask=valid.tolist()))
        fig.suptitle(identifier+' episode1 | Original surface markers; projection does not certify full form',fontsize=11);fig.tight_layout();target=OUT/(identifier+'-original-review.png');assert not target.exists();fig.savefig(target,dpi=120);plt.close(fig)
        images.append(dict(path=target.name,sha256=hashlib.sha256(target.read_bytes()).hexdigest()));reviews.append(dict(recording=identifier,archive=archive,member=record['source_member'],sha256=record['source_sha256'],original_episode=episode,snapshots=snaps))
    receipt.write_text(json.dumps(dict(kind='original-development-marker-and-projection-review-not-camera-accuracy',records=reviews,images=images,new_human_labels=0),indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print('Three fixed original traces and nine timestamped marker snapshots preserved')


if __name__=='__main__':main()
