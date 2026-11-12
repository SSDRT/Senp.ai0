"""Explicit observed-marker frontal projection; never an anatomical/MP33 angle.

Retain the out-of-plane component and projection conditioning separately. A
projection alone cannot certify abduction form: it discards a measured component.
No inferred joints, temporal repair, source-peak normalization or axis fitting.
"""
import hashlib
import numpy as np
from uiprmd_vicon import INDEX, MARKERS, markers
from uiprmd_central_torso import verify_development_record

PIPELINE = 'uiprmd-vicon39-frontal-arm-projection-research/1'
MINIMUM_PROJECTED_FRACTION = .25
FEATURES = {s: 'angle.'+s+'_arm_frontal_projection' for s in ['left','right']}
BACKEND_SHA256 = hashlib.sha256((PIPELINE+'|100Hz|mm|globalXYZ|orthogonal-shoulder-torso|projection>=0.25|'+','.join(MARKERS)).encode()).hexdigest()


def frontal_arm_features(array):
    points = markers(array)
    torso = points[:,[INDEX[n] for n in ['LSHO','RSHO','LASI','RASI']]]
    present = np.isfinite(torso).all(axis=(1,2)) & (np.linalg.norm(torso,axis=2)>0).all(axis=1)
    def unit(vector, mask):
        length = np.linalg.norm(vector,axis=1)
        ok = mask & np.isfinite(length) & (length>1e-12)
        return np.divide(vector,length[:,None],out=np.full_like(vector,np.nan),where=ok[:,None]),ok
    down, valid = unit((torso[:,2]+torso[:,3]-torso[:,0]-torso[:,1])/2,present)
    across = torso[:,1]-torso[:,0]
    lateral, valid = unit(across-np.sum(across*down,axis=1)[:,None]*down,valid)
    normal, valid = unit(np.cross(lateral,down),valid)
    result = {}
    for side,prefix in [('left','L'),('right','R')]:
        elbow = points[:,INDEX[prefix+'ELB']]
        arm, observed = unit(elbow-points[:,INDEX[prefix+'SHO']],valid & np.isfinite(elbow).all(axis=1) & (np.linalg.norm(elbow,axis=1)>0))
        d = np.sum(arm*down,axis=1)
        l = np.sum(arm*lateral,axis=1)
        n = np.sum(arm*normal,axis=1)
        fraction = np.hypot(d,l)
        conditioned = observed & (fraction>=MINIMUM_PROJECTED_FRACTION)
        result[FEATURES[side]] = np.where(conditioned,np.degrees(np.arctan2(np.abs(l),d)),np.nan)
        result['angle.'+side+'_arm_out_of_frontal_plane'] = np.where(observed,np.degrees(np.arctan2(np.abs(n),fraction)),np.nan)
        result['relation.'+side+'_arm_projected_fraction'] = np.where(observed,fraction,np.nan)
    return result
