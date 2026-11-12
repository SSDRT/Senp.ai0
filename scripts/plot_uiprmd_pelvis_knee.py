"""Fixed original squat marker snapshots and explicit measured plane components."""
import hashlib,json,zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA
from inspect_uiprmd_development import read_development_member,parse_publisher_array
from prepare_uiprmd_pelvis_knee import OUT,verify_protocol
from uiprmd_vicon import INDEX,markers,measurement_features
from uiprmd_pelvis_knee import pelvis_knee_features,MINIMUM_PROJECTED_FRACTION


def main():
    spec,_=verify_protocol();receipt=OUT/'original-marker-review.json'
    if receipt.exists():raise FileExistsError('Preserve original review')
    manifest=json.loads((DATA/'development-vicon/manifest.json').read_bytes());images=[];reviews=[]
    edges=[('LSHO','RSHO'),('LSHO','LELB'),('RSHO','RELB'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    def finite(v):return float(v) if np.isfinite(v) else None
    for identifier in spec['review_recordings']:
        entry=next(x for x in manifest['records'] if x['id']==identifier);record=json.loads((DATA/'development-vicon'/entry['file']).read_bytes());archive='Movements.zip' if record['correct'] else 'Incorrect Movements.zip'
        with zipfile.ZipFile(DATA/archive) as z:raw=read_development_member(z,record['source_member'],set(spec['subjects']))
        assert hashlib.sha256(raw).hexdigest()==record['source_sha256'];array,_=parse_publisher_array(raw,'Vicon');points=markers(array)
        old=measurement_features(array)['angle.left_knee'];values=pelvis_knee_features(array);new=values['angle.left_knee_pelvis_projection'];episode=record['episodes'][spec['review_episode']-1];start,end=episode['start_frame'],episode['end_frame'];indices=np.flatnonzero(np.isfinite(new[start:end+1]));peak=start+int(indices[np.argmin(new[start:end+1][indices])]) if len(indices) else start
        fig=plt.figure(figsize=(15,11),layout='constrained');grid=fig.add_gridspec(3,6,height_ratios=[1,.8,1.2]);time=np.arange(start,end+1)*10
        ax=fig.add_subplot(grid[0,:3])
        for v,name in [(old,'Original surface-marker ABC'),(new,'Pelvis-axis projected knee'),(values['angle.left_thigh_out_of_pelvis_plane'],'Retained thigh normal component'),(values['angle.left_shin_out_of_pelvis_plane'],'Retained shin normal component')]:ax.plot(time,v[start:end+1],label=name)
        ax.set_xlabel('Original acquisition ms');ax.set_ylabel('Degrees');ax.grid(alpha=.25);ax.legend(fontsize=8)
        ax=fig.add_subplot(grid[0,3:])
        for name in ['thigh','shin']:ax.plot(time,values['relation.left_'+name+'_projected_fraction'][start:end+1],label=name+' projected-length fraction')
        ax.axhline(MINIMUM_PROJECTED_FRACTION,color='red',linestyle='--',label='Frozen conditioning gate');ax.set_ylim(0,1.05);ax.set_xlabel('Original acquisition ms');ax.legend(fontsize=8);ax.grid(alpha=.25)
        ax=fig.add_subplot(grid[1,:]);ax.plot(time,new[start:end+1]-old[start:end+1],label='Projected minus original ABC, no coordinate correction');ax.set_ylabel('Degree difference');ax.set_xlabel('Original acquisition ms');ax.legend(fontsize=8);ax.grid(alpha=.25)
        snapshots=[]
        for i,index in enumerate([start,peak,end]):
            ax=fig.add_subplot(grid[2,i*2:(i+1)*2],projection='3d');p=points[index];present=np.isfinite(p).all(axis=1)&(np.linalg.norm(p,axis=1)>0)
            for a,b in edges:
                ids=[INDEX[a],INDEX[b]]
                if present[ids].all():ax.plot(*p[ids].T,color='steelblue')
            ax.scatter(*p[present].T,s=4,color='grey');ax.set_title(f'Original frame{index} / {index*10}ms',fontsize=9);ax.set_xlabel('Xmm');ax.set_ylabel('Ymm');ax.set_zlabel('Zmm');ax.set_box_aspect([1,1,1]);ax.view_init(15,45)
            snapshots.append(dict(frame=index,timestamp_ms=index*10,original_abc_degrees=finite(old[index]),projected_degrees=finite(new[index]),thigh_normal_degrees=finite(values['angle.left_thigh_out_of_pelvis_plane'][index]),shin_normal_degrees=finite(values['angle.left_shin_out_of_pelvis_plane'][index]),original_points=p.tolist(),observed_marker_mask=present.tolist()))
        fig.suptitle(identifier+f" episode{spec['review_episode']} | Surface-marker plane surrogate, not anatomical/camera truth",fontsize=11);path=OUT/(identifier+'-original-review.png');assert not path.exists();fig.savefig(path,dpi=120);plt.close(fig)
        images.append(dict(path=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest()));reviews.append(dict(recording=identifier,archive=archive,member=record['source_member'],source_sha256=record['source_sha256'],original_episode=episode,snapshots=snapshots))
    receipt.write_text(json.dumps(dict(kind='original-marker-plane-projection-review-not-human-pose-accuracy',images=images,records=reviews,new_human_labels=0),indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print('Three fixed original squat traces and nine timestamped marker frames saved')


if __name__=='__main__':main()
