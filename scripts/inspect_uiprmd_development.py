"""Inspect development-file integrity and shapes without opening held-out trajectories."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile
from fetch_uiprmd import DATA, ROOT, checksum

NAME=re.compile(r'm(?P<movement>\d{2})_s(?P<subject>\d{2})(?:_e(?P<episode>\d{2}))?_(?P<kind>positions|angles)(?P<incorrect>_inc)?\.txt')


def read_development_member(archive,member,allowed_subjects):
    path=PurePosixPath(member.replace('\\','/'))
    match=NAME.fullmatch(path.name)
    if '..' in path.parts or match is None or match['subject'] not in allowed_subjects:
        raise PermissionError('Trajectory is not in the frozen development split')
    return archive.read(member)


def parse_publisher_array(raw,sensor):
    import numpy as np
    first=next((line for line in raw.splitlines() if line.strip()),b'')
    delimiter=',' if b',' in first else None
    array=np.loadtxt(io.BytesIO(raw),delimiter=delimiter,ndmin=2)
    columns=66 if sensor=='Kinect' else 117
    if array.shape[1]!=columns:raise ValueError(f'Expected {columns} columns, got {array.shape[1]}')
    return array,'comma' if delimiter else 'whitespace'


def main():
    import numpy as np
    protocol=json.loads((ROOT/'models/uiprmd-evaluation-protocol.json').read_text())
    acquisition=json.loads((DATA/'acquisition.json').read_text())
    allowed=set(protocol['subjects']['development'])
    assert allowed=={'01','02','03','04'} and set(protocol['subjects']['test'])=={'08','09','10'}
    rows=[];inventory=[];failures=[]
    for info in acquisition['files']:
        if not info['file'].endswith('.zip'):continue
        path=DATA/info['file']
        if checksum(path,'sha256')!=info['sha256']:raise ValueError('Archive changed after acquisition')
        with zipfile.ZipFile(path) as archive:
            for member in archive.namelist():
                match=NAME.fullmatch(PurePosixPath(member.replace('\\','/')).name)
                if match is None or 'm'+match['movement'] not in protocol['variants']:continue
                if '/Kinect/' not in member and '/Vicon/' not in member:continue
                sensor='Kinect' if '/Kinect/' in member else 'Vicon'
                inventory.append({'archive':info['file'],'member':member,'subject':match['subject'],
                    'movement':'m'+match['movement'],'kind':match['kind'],'incorrect':bool(match['incorrect']),
                    'sensor':sensor,'segmented':match['episode'] is not None,'trajectory_opened':match['subject'] in allowed})
                if match['subject'] not in allowed:continue
                try:
                    raw=read_development_member(archive,member,allowed)
                    array,delimiter=parse_publisher_array(raw,sensor)
                    rows.append({'archive':info['file'],'member':member,'subject':match['subject'],'sensor':sensor,
                        'frames':array.shape[0],'columns':array.shape[1],'delimiter':delimiter,'finite_fraction':float(np.isfinite(array).mean()),
                        'sha256':hashlib.sha256(raw).hexdigest()})
                except (ValueError,UnicodeError) as error:
                    failures.append({'archive':info['file'],'member':member,'error':str(error)})
    report={'schema':'uiprmd-development-format-inspection/1','kind':'format-inspection-not-model-training-or-independent-precision',
        'dataset_doi':acquisition['dataset_doi'],'source_archive_count':4,'metadata_inventory':inventory,
        'development_trajectories_opened':len(rows)+len(failures),'valid_shape_measurement_files':len(rows),'rows':rows,'failures':failures,
        'subjects_opened':sorted({r['subject'] for r in rows}),'validation_trajectories_opened':False,
        'final_test_subject_trajectories_opened':False,'geometry_adapter_validated':False,'readiness_points_earned':0,
        'warning':'Kinect positions are parent-relative except the root. Vicon Positions are labelled marker trajectories, a different schema from Vicon Angles. Shapes and finite values alone validate neither geometry adapter.'}
    (DATA/'development-inspection.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['development_trajectories_opened','subjects_opened','failures','final_test_subject_trajectories_opened','geometry_adapter_validated']},indent=2))


if __name__=='__main__':main()
