"""Inspect native components and original markers, without anatomical remapping."""
import hashlib,json,zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from audit_uiprmd_native_angles import AUDIT
from uiprmd_native_angles import read_development_member,parse_native_angles,component,SAMPLE_PERIOD_MS
from inspect_uiprmd_development import read_development_member as read_positions,parse_publisher_array
from fetch_uiprmd import ROOT,DATA,checksum
from uiprmd_vicon import markers,INDEX


def verify_qa_protocol():
    path=ROOT/'models/uiprmd-native-angle-intake-protocol.json';original=json.loads(path.read_bytes());amend=json.loads((ROOT/'models/uiprmd-native-angle-qa-amendment.json').read_bytes())
    assert amend['qa_only'] and amend['original_protocol_sha256']==checksum(path,'sha256') and not amend['allow_final_test_access'] and not amend['allow_validation_access']
    assert original['subjects']==['01','02','03','04'] and not original['fit_model'] and not original['map_to_camera_ABC']
    for r in original['receipts']:
        p=ROOT/r['path'];raw=p.read_bytes().replace(b'\r\n',b'\n') if 'sha256_lf' in r else p.read_bytes()
        expected=amend['plot_source_sha256_lf'] if r['path']=='scripts/plot_uiprmd_native_angles.py' else r.get('sha256_lf',r.get('sha256'))
        if hashlib.sha256(raw).hexdigest()!=expected:raise ValueError('Frozen source/input changed '+r['path'])
    for r in amend['receipts']:
        if checksum(ROOT/r['path'],'sha256')!=r['sha256']:raise ValueError('QA evidence changed')


def main():
    verify_qa_protocol();receipt=AUDIT/'native-angle-original-marker-qa.json'
    if receipt.exists():raise FileExistsError('Preserve original marker review')
    report=json.loads((AUDIT/'native-angle-development-inspection.json').read_bytes())
    cases=[('m01-s01-correct','left_knee'),('m07-s01-correct','right_shoulder'),('m07-s04-correct','right_shoulder')]
    images=[];access=[];snapshots=[];fig=plt.figure(figsize=(14,15))
    for row,(record_id,joint) in enumerate(cases):
        r=next(x for x in report['records'] if x['id']==record_id)
        with zipfile.ZipFile(DATA/r['archive']) as z:
            raw=read_development_member(z,r['archive'],r['member']);assert hashlib.sha256(raw).hexdigest()==r['sha256'];angles=parse_native_angles(raw);access.append(dict(archive=r['archive'],member=r['member'],sha256=hashlib.sha256(raw).hexdigest()))
            member=r['member'].replace('/Angles/','/Positions/').replace('_angles','_positions')
            raw_positions=read_positions(z,member,{'01','02','03','04'});array,_=parse_publisher_array(raw_positions,'Vicon');access.append(dict(archive=r['archive'],member=member,sha256=hashlib.sha256(raw_positions).hexdigest()))
        p=DATA/'development-vicon'/(record_id+'.json');position=json.loads(p.read_bytes());assert hashlib.sha256(raw_positions).hexdigest()==position['source_sha256']
        assert len(array)==len(angles)==len(position['frames']);e=r['episodes'][0];assert e['same_position_bounds']
        feature='angle.'+joint;proxy=np.array([x['values'].get(feature,np.nan) for x in position['frames']]);t=np.arange(len(angles))*SAMPLE_PERIOD_MS/1000
        f,ax=plt.subplots(2,1,figsize=(12,6),sharex=True)
        for c in range(3):ax[0].plot(t,component(angles,joint,c),label=f'native recorded component{c}',linewidth=.7)
        ax[0].set_ylabel('Native degrees');ax[0].legend(loc='upper right',fontsize=8);ax[0].set_title(record_id+' '+joint+' | raw components; no filtering or unwrap')
        ax[1].plot(t,proxy,color='black',linewidth=.8,label='existing surface-marker ABC proxy');ax[1].legend(fontsize=8);ax[1].set_ylabel('Marker-proxy degrees');ax[1].set_xlabel('Original acquisition time (s),100Hz')
        for a in ax:
            a.axvspan(e['start_frame']*.01,e['end_frame']*.01,color='orange',alpha=.12);a.grid(alpha=.2)
        f.suptitle('Different measurement definitions; no camera/form accuracy claim',fontsize=10);f.tight_layout();path=AUDIT/(record_id+'-native-components.png');assert not path.exists();f.savefig(path,dpi=140);plt.close(f);images.append(dict(path=path.name,sha256=checksum(path,'sha256')))
        start,end=e['start_frame'],e['end_frame'];candidates=np.arange(start,end+1);finite=np.isfinite(proxy[candidates])
        if not finite.any():raise ValueError('First original episode has no observed marker proxy; preserve incomplete QA')
        baseline=int(candidates[finite][0]);initial=proxy[baseline]
        peak=int(candidates[finite][np.argmax(np.abs(proxy[candidates[finite]]-initial))])
        xyz=markers(array)
        pairs=[('LSHO','RSHO'),('LSHO','LELB'),('LELB','LWRA'),('LELB','LWRB'),('RSHO','RELB'),('RELB','RWRA'),('RELB','RWRB'),('LSHO','LASI'),('RSHO','RASI'),('LASI','RASI'),('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
        for col,index in enumerate([start,peak]):
            a=fig.add_subplot(3,2,row*2+col+1,projection='3d');points=xyz[index];valid=np.isfinite(points).all(axis=1)&(np.linalg.norm(points,axis=1)>0)
            a.scatter(*points[valid].T,s=8,color='tab:blue')
            for first,last in pairs:
                i,j=INDEX[first],INDEX[last]
                if valid[i] and valid[j]:a.plot(*points[[i,j]].T,color='tab:blue',linewidth=1)
            a.set_title(f'{record_id} frame{index} / original{index*10}ms\n'+('First episode start' if col==0 else 'Largest observed proxy excursion'),fontsize=9);a.set_xlabel('Lab X(mm)');a.set_ylabel('Lab Y(mm)');a.set_zlabel('Lab Z(mm)');a.view_init(elev=20,azim=-65)
            snapshots.append(dict(id=record_id,original_frame=index,original_timestamp_ms=index*10,source_position_sha256=position['source_sha256'],native_angle_sha256=r['sha256'],valid_original_markers=int(valid.sum()),marker_proxy_degrees=float(proxy[index]) if np.isfinite(proxy[index]) else None,peak_excursion_baseline_original_frame=baseline,original_episode_start_proxy_missing=not bool(np.isfinite(proxy[start])),native_components_degrees=[float(component(angles,joint,c)[index]) for c in range(3)]))
    fig.suptitle('Original Vicon surface-marker coordinates; no reconstructed joint centres',fontsize=12);fig.tight_layout();p=AUDIT/'native-angle-original-marker-snapshots.png';assert not p.exists();fig.savefig(p,dpi=140);plt.close(fig);images.append(dict(path=p.name,sha256=checksum(p,'sha256')))
    receipt.write_text(json.dumps(dict(schema='uiprmd-native-angle-original-marker-qa/1',native_recordings=3,original_marker_frames=6,images=images,snapshots=snapshots,access=access,zero_marker_samples_not_drawn_or_repaired=True,new_human_labels=0,final_subjects_opened=False,production_changed=False),indent=2)+'\n',encoding='utf-8',newline='\n');print('Original native component plots and six marker frames saved')


if __name__=='__main__':main()
