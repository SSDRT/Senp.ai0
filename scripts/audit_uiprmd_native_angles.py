"""Schema/grid diagnostics on frozen development only; no form-model fitting."""
import hashlib,json,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
from fetch_uiprmd import ROOT,DATA,checksum
from uiprmd_native_angles import *

AUDIT=ROOT/'test-artifacts/exercise-knowledge/uiprmd-native-angle-audit-20261028'


def verify_protocol():
    path=ROOT/'models/uiprmd-native-angle-intake-protocol.json';spec=json.loads(path.read_bytes())
    if spec['subjects']!=sorted(DEVELOPMENT) or spec['allow_validation_access'] or spec['allow_final_test_access'] or spec['fit_model'] or spec['map_to_camera_ABC']:raise PermissionError('Development-only numeric intake required')
    for row in spec['receipts']:
        p=ROOT/row['path'];raw=p.read_bytes().replace(b'\r\n',b'\n') if 'sha256_lf' in row else p.read_bytes()
        if hashlib.sha256(raw).hexdigest()!=row.get('sha256_lf',row.get('sha256')):raise ValueError('Frozen source/input changed '+row['path'])
    table=next(t for t in ET.parse(AUDIT/'paper.xml').getroot().iter('table-wrap') if ''.join(t.find('label').itertext()).strip().lower()=='table 2')
    actual=[]
    for tr in table.findall('.//tr')[1:]:
        cells=[' '.join(''.join(c.itertext()).split()) for c in tr]
        assert int(cells[0])==len(actual)+1
        actual.append(cells[2].lower().replace(' (absolute)','_absolute').replace(' ','_'))
    if tuple(actual)!=JOINTS:raise ValueError('Native angle order differs from primary Table2')
    return spec


def summary(values):
    finite=values[np.isfinite(values)]
    return dict(finite_samples=int(len(finite)),minimum=float(finite.min()) if len(finite) else None,maximum=float(finite.max()) if len(finite) else None,median=float(np.median(finite)) if len(finite) else None)


def main():
    verify_protocol();out=AUDIT/'native-angle-development-inspection.json'
    if out.exists():raise FileExistsError('Preserve original inspection')
    positions=json.loads((DATA/'development-vicon/manifest.json').read_bytes());assert positions['subjects_opened']==sorted(DEVELOPMENT)
    old=json.loads((DATA/'development-inspection.json').read_bytes());known={(r['archive'],r['member']):r['sha256'] for r in old['rows']}
    access=[];records=[]
    def read(z,archive,member):
        raw=read_development_member(z,archive,member);digest=hashlib.sha256(raw).hexdigest()
        if known[(archive,member)]!=digest:raise ValueError('Previously inspected angular source changed')
        access.append(dict(archive=archive,member=member,sha256=digest));return parse_native_angles(raw),digest
    for incorrect in [False,True]:
        archive='Incorrect Movements.zip' if incorrect else 'Movements.zip';seg_archive='Incorrect Segmented Movements.zip' if incorrect else 'Segmented Movements.zip';suffix='_inc' if incorrect else ''
        with zipfile.ZipFile(DATA/archive) as z,zipfile.ZipFile(DATA/seg_archive) as sz:
            for subject in sorted(DEVELOPMENT):
                for movement in ['m01','m07']:
                    member=f'{archive.removesuffix(".zip")}/Vicon/Angles/{movement}_s{subject}_angles{suffix}.txt';array,digest=read(z,archive,member)
                    record_id=f'{movement}-s{subject}-'+('incorrect' if incorrect else 'correct')
                    position_entry=next(r for r in positions['records'] if r['id']==record_id);position=json.loads((DATA/'development-vicon'/position_entry['file']).read_bytes())
                    assert checksum(DATA/'development-vicon'/position_entry['file'],'sha256')==position_entry['sha256']
                    episodes=[]
                    for episode in range(1,11):
                        sm=f'{seg_archive.removesuffix(".zip")}/Vicon/Angles/{movement}_s{subject}_e{episode:02}_angles{suffix}.txt';segment,segment_sha=read(sz,seg_archive,sm)
                        expected=next(e for e in position['episodes'] if e['episode']==episode)
                        try:
                            start,end=aligned_angle_bounds(array,segment);error=None;difference=float(np.max(np.abs(array[start:end+1]-segment)))
                        except ValueError as exc:start=end=None;error=str(exc);difference=None
                        episodes.append(dict(episode=episode,member=sm,sha256=segment_sha,start_frame=start,end_frame=end,position_start_frame=expected['start_frame'],position_end_frame=expected['end_frame'],same_position_bounds=(start,end)==(expected['start_frame'],expected['end_frame']),maximum_text_rounding_difference_degrees=difference,alignment_error=error))
                    values=triplets(array);finite=numeric_mask(array);selected=['left_knee','right_knee','left_hip','right_hip','left_shoulder','right_shoulder','left_elbow','right_elbow']
                    joints={name:dict(components={str(c):summary(values[:,INDEX[name],c]) for c in range(3)},all_zero_triplets=int(np.count_nonzero(np.all(values[:,INDEX[name],:]==0,axis=1))),adjacent_component_steps_over_180_degrees=int(np.count_nonzero((np.abs(np.diff(values[:,INDEX[name],:],axis=0))>180)&finite[1:,INDEX[name],:]&finite[:-1,INDEX[name],:]))) for name in selected}
                    records.append(dict(id=record_id,subject=subject,movement=movement,original_record_correct=not incorrect,archive=archive,member=member,sha256=digest,frames=len(array),position_frames=len(position['frames']),same_position_frame_count=len(array)==len(position['frames']),episodes=episodes,joints=joints,finite_component_fraction=float(finite.mean()),all_zero_triplets=int(np.count_nonzero(np.all(values==0,axis=2))),entire_zero_frames=int(np.count_nonzero(np.all(array==0,axis=1))),numeric_zero_interpreted_as_missing=False,confidence_inferred=False))
    assert len(records)==16 and len(access)==176 and sum(len(r['episodes']) for r in records)==160
    report=dict(schema='uiprmd-native-angular-development-inspection/1',kind='measurement-schema-and-time-grid-not-form-accuracy',pipeline=PIPELINE,subjects_opened=sorted(DEVELOPMENT),validation_trajectories_opened=False,final_test_subjects_opened=False,original_recordings=16,original_episodes=160,archive_members_read=176,primary_joint_order_verified=True,recorded_components_preserved=True,same_position_frame_counts=all(r['same_position_frame_count'] for r in records),episode_position_boundary_mismatches=sum(not e['same_position_bounds'] for r in records for e in r['episodes']),ambiguous_or_missing_angular_excerpts=sum(e['alignment_error'] is not None for r in records for e in r['episodes']),model_fitted=False,model_predictions=0,camera_confidence_inferred=False,anatomical_ABC_mapping_performed=False,raw_MP33_changed=False,production_changed=False,human_rep_precision=None,readiness_points_earned=0,records=records,access=access)
    out.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n');print(json.dumps({k:v for k,v in report.items() if k not in {'records','access'}},indent=2))


if __name__=='__main__':main()
