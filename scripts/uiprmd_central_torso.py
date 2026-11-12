"""Research-only arm/central-torso vector angle, not anatomical shoulder abduction.

Direct observed Vicon surface markers only. No inferred joints, repaired samples,
Euler-angle mapping, body normalization or MediaPipe confidence is introduced.
"""
import hashlib
import numpy as np
from uiprmd_vicon import INDEX, MARKERS, markers

PIPELINE = 'uiprmd-vicon39-central-torso-driver-research/1'
FEATURES = {side: 'angle.'+side+'_arm_central_torso' for side in ['left', 'right']}
BACKEND_SHA256 = hashlib.sha256((PIPELINE+'|100Hz|mm|globalXYZ|'+','.join(MARKERS)).encode()).hexdigest()


def central_torso_features(array):
    """Angle of elbow-minus-shoulder against hip-midpoint-minus-shoulder-midpoint.

    Every one of the four torso markers and the selected elbow must be present.
    Midpoints define a derived vector; they never replace recorded marker rows.
    """
    points = markers(array)
    torso_names = ['LSHO', 'RSHO', 'LASI', 'RASI']
    torso = points[:, [INDEX[n] for n in torso_names]]
    torso_valid = np.isfinite(torso).all(axis=(1, 2)) & (np.linalg.norm(torso, axis=2)>0).all(axis=1)
    down = (torso[:, 2]+torso[:, 3]-torso[:, 0]-torso[:, 1])/2
    result = {}
    for side, prefix in [('left', 'L'), ('right', 'R')]:
        elbow = points[:, INDEX[prefix+'ELB']]
        arm = elbow-points[:, INDEX[prefix+'SHO']]
        valid = torso_valid & np.isfinite(elbow).all(axis=1) & (np.linalg.norm(elbow, axis=1)>0)
        denominator = np.linalg.norm(arm, axis=1)*np.linalg.norm(down, axis=1)
        valid &= np.isfinite(denominator) & (denominator>1e-12)
        product = np.multiply(arm, down, out=np.zeros_like(arm), where=valid[:,None])
        cosine = np.divide(np.sum(product, axis=1), denominator, out=np.zeros_like(denominator), where=valid)
        result[FEATURES[side]] = np.where(valid, np.degrees(np.arccos(np.clip(cosine, -1, 1))), np.nan)
    return result


def verify_development_record(record):
    if (record['subject'] not in {'01','02','03','04'} or record['movement'] not in {'m01','m07'} or
        record['schema']!='uiprmd-vicon-recording/1' or record['sample_period_ms']!=10):
        raise PermissionError('Only the frozen development position exports are eligible')
    if len(record['episodes'])!=10 or any(e['correct'] is not record['correct'] for e in record['episodes']):
        raise ValueError('Preserve all original labels and episode boundaries')

