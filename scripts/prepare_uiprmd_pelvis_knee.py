"""Frozen development-only derived feature export. Original exports remain intact."""
import copy, hashlib, json, zipfile
from pathlib import Path
import numpy as np
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from uiprmd_pelvis_knee import PIPELINE, BACKEND_SHA256, MINIMUM_PROJECTED_FRACTION, pelvis_knee_features, verify_frontal_export

OUT = ROOT/'test-artifacts/exercise-knowledge/pelvis-knee-projection-20261029'


def verify_protocol():
    path=ROOT/'models/uiprmd-pelvis-knee-protocol.json';spec=json.loads(path.read_bytes())
    if (spec['subjects']!=['01','02','03','04'] or spec['allow_validation_access'] or spec['allow_final_test_access'] or
        spec['pipeline']!=PIPELINE or spec['development_denominator']!=160 or
        spec['backend_sha256']!=BACKEND_SHA256 or spec['minimum_projected_fraction']!=MINIMUM_PROJECTED_FRACTION or
        not spec['projection_discards_plane_components'] or spec['out_of_plane_form_policy_completed']):
        raise PermissionError('Frozen development-only experiment required')
    for row in spec['receipts']:
        p=ROOT/row['path'];raw=p.read_bytes()
        if 'sha256_lf' in row:raw=raw.replace(b'\r\n',b'\n')
        if hashlib.sha256(raw).hexdigest()!=row.get('sha256_lf',row.get('sha256')):
            raise ValueError('Frozen source/input changed: '+row['path'])
    return spec,path


def main():
    spec,path=verify_protocol();destination=OUT/'development';destination.mkdir(parents=True,exist_ok=False)
    source=ROOT/'test-artifacts/exercise-knowledge/frontal-arm-projection-20261029/development';manifest=json.loads((source/'manifest.json').read_bytes())
    if manifest['subjects_opened']!=spec['subjects'] or manifest['test_trajectories_opened'] or manifest['validation_trajectories_opened']:
        raise PermissionError('Frozen development split changed')
    rows=[];access=[];diagnostics=[]
    for entry in manifest['records']:
        original_path=source/entry['file'];record=json.loads(original_path.read_bytes());verify_frontal_export(record)
        assert checksum(original_path,'sha256')==entry['sha256']
        archive='Movements.zip' if record['correct'] else 'Incorrect Movements.zip'
        member=record['source_member']
        if '/Vicon/Positions/' not in member:raise PermissionError('Original Vicon position evidence required')
        with zipfile.ZipFile(DATA/archive) as z:
            raw=read_development_member(z,member,set(spec['subjects']))
        digest=hashlib.sha256(raw).hexdigest()
        assert digest==record['source_sha256'];access.append(dict(archive=archive,member=member,sha256=digest))
        array,_=parse_publisher_array(raw,'Vicon');assert len(array)==len(record['frames'])
        features=pelvis_knee_features(array)
        candidate=copy.deepcopy(record);candidate.update(schema='uiprmd-pelvis-knee-recording/1',pipeline=PIPELINE,backend_sha256=BACKEND_SHA256)
        for index,frame in enumerate(candidate['frames']):
            assert frame['timestamp_ms']==index*10
            for name,values in (features.items() if record['movement']=='m01' else []):
                if np.isfinite(values[index]):frame['values'][name]=float(values[index])
        assert candidate['episodes']==record['episodes']
        target=destination/entry['file'];target.write_text(json.dumps(candidate,separators=(',',':'),allow_nan=False)+'\n',encoding='utf-8',newline='\n')
        rows.append(dict(entry,sha256=checksum(target,'sha256'),original_file=original_path.relative_to(ROOT).as_posix(),original_sha256=entry['sha256']))
        diagnostics.append(dict(record=entry['id'],features={name:dict(observed=int(np.isfinite(v).sum()),missing=int((~np.isfinite(v)).sum()),minimum=float(np.nanmin(v)) if np.isfinite(v).any() else None,maximum=float(np.nanmax(v)) if np.isfinite(v).any() else None) for name,v in features.items()}))
    assert len(rows)==16
    output=dict(schema='uiprmd-pelvis-knee-development-export/1',subjects_opened=spec['subjects'],validation_trajectories_opened=False,test_trajectories_opened=False,pipeline=PIPELINE,backend_sha256=BACKEND_SHA256,records=rows,access=access,diagnostics=diagnostics,protocol_sha256=checksum(path,'sha256'),new_human_labels=0,readiness_points_earned=0)
    (destination/'manifest.json').write_text(json.dumps(output,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print('16 original development recordings exported; all160 labels/bounds retained; no validation/final reads')


if __name__=='__main__':main()
