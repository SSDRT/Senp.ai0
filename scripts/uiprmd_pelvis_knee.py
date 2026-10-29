"""Observed-marker knee angle in the plane orthogonal to the pelvis lateral axis.

This is an explicit surface-marker projection, not anatomical joint-centre
reconstruction, a clinical flexion angle or MP33 evidence. Retain both discarded
ray components; projection alone cannot certify frontal-plane knee form.
"""
import hashlib
import numpy as np
from uiprmd_vicon import INDEX,MARKERS,markers
from uiprmd_frontal_arm import PIPELINE as ARM_PIPELINE

PIPELINE='uiprmd-vicon39-pelvis-knee-projection-research/1'
MINIMUM_PROJECTED_FRACTION=.25
FEATURES={s:'angle.'+s+'_knee_pelvis_projection' for s in ['left','right']}
BACKEND_SHA256=hashlib.sha256((PIPELINE+'|'+ARM_PIPELINE+'|100Hz|mm|orthogonal-to-RASI-minus-LASI|both-rays-projection>=0.25|'+','.join(MARKERS)).encode()).hexdigest()


def pelvis_knee_features(array):
    points=markers(array).copy()
    points[~np.isfinite(points).all(axis=2)|(np.linalg.norm(points,axis=2)==0)]=np.nan
    def p(name):return points[:,INDEX[name]]
    def unit(v):
        length=np.linalg.norm(v,axis=1)
        return np.divide(v,length[:,None],out=np.full_like(v,np.nan),where=(np.isfinite(length)&(length>1e-12))[:,None])
    lateral=unit(p('RASI')-p('LASI'));values={}
    for side,prefix in [('left','L'),('right','R')]:
        thigh=unit(p(prefix+'ASI')-p(prefix+'KNE'));shin=unit(p(prefix+'ANK')-p(prefix+'KNE'))
        components=[np.sum(v*lateral,axis=1) for v in [thigh,shin]]
        rays=[v-c[:,None]*lateral for v,c in zip([thigh,shin],components)]
        fractions=[np.linalg.norm(v,axis=1) for v in rays]
        conditioned=np.isfinite(lateral).all(axis=1)&np.isfinite(thigh).all(axis=1)&np.isfinite(shin).all(axis=1)
        for fraction in fractions:conditioned &= fraction>=MINIMUM_PROJECTED_FRACTION
        first,second=[unit(v) for v in rays]
        cosine=np.sum(first*second,axis=1)
        values[FEATURES[side]]=np.where(conditioned,np.degrees(np.arccos(np.clip(cosine,-1,1))),np.nan)
        for name,c,fraction in zip(['thigh','shin'],components,fractions):
            values['angle.'+side+'_'+name+'_out_of_pelvis_plane']=np.degrees(np.arcsin(np.clip(np.abs(c),0,1)))
            values['relation.'+side+'_'+name+'_projected_fraction']=fraction
    return values


def verify_frontal_export(record):
    if (record['subject'] not in {'01','02','03','04'} or record['movement'] not in {'m01','m07'} or
        record['schema']!='uiprmd-frontal-arm-recording/1' or record['sample_period_ms']!=10):
        raise PermissionError('Only frozen original development projection records are eligible')
    if len(record['episodes'])!=10 or any(e['correct'] is not record['correct'] for e in record['episodes']):raise ValueError('Original labels/bounds required')
