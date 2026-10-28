"""Native Vicon angle intake, not marker XYZ, MP33, or anatomical ABC angles.

Joint order is UI-PRMD Table2 (doi:10.3390/data3010002), Vicon Angles.
Keep components in recorded order; zero degrees can be neutral, not missing.
Finiteness is a numeric mask, never tracking confidence or acquisition validity.
"""
from pathlib import PurePosixPath
import io,re
import numpy as np

PIPELINE='uiprmd-vicon39-native-angular-intake/1'
SAMPLE_PERIOD_MS=10
ANGLE_EXPORT_TOLERANCE_DEGREES=1e-6
JOINTS=tuple(('head_absolute left_head right_head left_neck right_neck left_clavicle right_clavicle '
        'thorax_absolute left_thorax right_thorax pelvis_absolute left_pelvis right_pelvis '
        'left_hip right_hip left_femur right_femur left_knee right_knee left_tibia right_tibia '
        'left_ankle right_ankle left_foot right_foot left_toe right_toe left_shoulder right_shoulder '
        'left_elbow right_elbow left_radius right_radius left_wrist right_wrist '
        'left_upperhand right_upperhand left_hand right_hand').split())
INDEX={name:i for i,name in enumerate(JOINTS)}
DEVELOPMENT=frozenset({'01','02','03','04'})
NAME=re.compile(r'm(?P<movement>01|07)_s(?P<subject>\d{2})(?:_e(?P<episode>\d{2}))?_angles(?P<incorrect>_inc)?\.txt')


def validate_development_member(archive_name,member):
    path=PurePosixPath(member.replace('\\','/'))
    segmented='Segmented' in archive_name
    if archive_name not in {'Movements.zip','Incorrect Movements.zip','Segmented Movements.zip','Incorrect Segmented Movements.zip'}:raise PermissionError('Unknown publisher archive')
    match=NAME.fullmatch(path.name)
    if (path.is_absolute() or '..' in path.parts or len(path.parts)!=4 or path.parts[:3]!=(archive_name.removesuffix('.zip'),'Vicon','Angles') or
            match is None or match['subject'] not in DEVELOPMENT or bool(match['incorrect'])!=archive_name.startswith('Incorrect') or
            (match['episode'] is not None)!=segmented or (segmented and not 1<=int(match['episode'])<=10)):
        raise PermissionError('Only exact original development Vicon angle members are permitted')
    return match.groupdict()


def read_development_member(archive,archive_name,member):
    validate_development_member(archive_name,member)  # Reject before archive.read.
    return archive.read(member)


def parse_native_angles(raw):
    first=next((line for line in raw.splitlines() if line.strip()),b'')
    array=np.loadtxt(io.BytesIO(raw),delimiter=',' if b',' in first else None,ndmin=2)
    if array.ndim!=2 or array.shape[1]!=117 or not len(array):raise ValueError('Expected nonempty39 angular triplets,117 columns')
    return array


def triplets(array):
    array=np.asarray(array,dtype=float)
    if array.ndim!=2 or array.shape[1]!=117 or not len(array):raise ValueError('Expected117 native angular columns')
    return array.reshape(-1,39,3).copy()


def component(array,joint,index):
    if index not in {0,1,2}:raise ValueError('Only recorded component0,1,2 exist')
    return triplets(array)[:,INDEX[joint],index].copy()


def numeric_mask(array):return np.isfinite(triplets(array))


def as_camera_abc_features(array):
    raise PermissionError('Native Euler triplets are not MP33 ABC joint angles; no verified export-model/neutral-axis mapping or camera confidence')


def aligned_angle_bounds(full,segment):
    full,segment=np.asarray(full),np.asarray(segment)
    triplets(full);triplets(segment)
    candidates=np.flatnonzero(np.all(np.abs(full-segment[0])<=ANGLE_EXPORT_TOLERANCE_DEGREES,axis=1))
    matches=[int(i) for i in candidates if i+len(segment)<=len(full) and np.all(np.abs(full[i:i+len(segment)]-segment)<=ANGLE_EXPORT_TOLERANCE_DEGREES)]
    if len(matches)!=1:raise ValueError(f'Expected unique contiguous angular excerpt, found{len(matches)}')
    return matches[0],matches[0]+len(segment)-1
