"""Experimental complete-attempt skeletal descriptors; never modified pose evidence."""
import numpy as np
from uiprmd_vicon import INDEX, markers, angle, measurement_features

SCHEMA = 'uiprmd-vicon39-rep-quality/1'


def frame_features(array):
    points = markers(array).copy()
    points[~np.isfinite(points).all(axis=2) | (np.linalg.norm(points,axis=2)==0)] = np.nan
    def p(name):return points[:,INDEX[name]]
    def unit(v):
        length=np.linalg.norm(v,axis=1,keepdims=True)
        return np.divide(v,length,out=np.full_like(v,np.nan),where=length>1e-9)
    values=measurement_features(array)
    for side,prefix in [('left','L'),('right','R')]:
        values['angle.'+side+'_body_line']=angle(p(prefix+'SHO'),p(prefix+'ASI'),p(prefix+'ANK'))
    torso=(p('LSHO')+p('RSHO')-p('LASI')-p('RASI'))/2
    torso_length=np.linalg.norm(torso,axis=1)
    def distance(a,b):return np.linalg.norm(p(a)-p(b),axis=1)
    def ratio(a,b):
        valid=np.isfinite(b)&np.isfinite(torso_length)&(torso_length>1e-9)&(b>=.05*torso_length)
        return np.divide(a,b,out=np.full_like(a,np.nan),where=valid)
    values['relation.knee_width_over_feet']=ratio(distance('LKNE','RKNE'),distance('LANK','RANK'))
    values['relation.foot_width_over_hips']=ratio(distance('LANK','RANK'),distance('LASI','RASI'))
    left_wrist=(p('LWRA')+p('LWRB'))/2;right_wrist=(p('RWRA')+p('RWRB'))/2
    values['relation.hand_width_over_shoulders']=ratio(np.linalg.norm(left_wrist-right_wrist,axis=1),distance('LSHO','RSHO'))
    hip_axis=unit(p('RASI')-p('LASI'))
    shoulder_axis=unit(p('RSHO')-p('LSHO'))
    values['angle.shoulder_hip_axes']=np.degrees(np.arccos(np.clip(np.sum(hip_axis*shoulder_axis,axis=1),-1,1)))
    up=unit(torso-np.sum(torso*hip_axis,axis=1,keepdims=True)*hip_axis)
    normal=unit(np.cross(hip_axis,up))
    for side,prefix in [('left','L'),('right','R')]:
        arm=unit(p(prefix+'ELB')-p(prefix+'SHO'))
        plane=np.degrees(np.arcsin(np.clip(np.abs(np.sum(arm*normal,axis=1)),0,1)))
        plane[~np.isfinite(torso_length)|(torso_length<=1e-9)]=np.nan
        values['angle.'+side+'_arm_out_of_torso_plane']=plane
    return values


def aggregate(values,first,last):
    if first<0 or last<first or any(last>=len(v) for v in values.values()):
        raise ValueError('Attempt must lie within the original recording')
    result=[];names=[]
    for name,series in sorted(values.items()):
        window=series[first:last+1];present=window[np.isfinite(window)]
        quantiles=np.quantile(present,[.1,.5,.9]).tolist() if len(present) else [float('nan')]*3
        result.extend(quantiles+[len(present)/len(window)])
        names.extend([name+'.q10',name+'.q50',name+'.q90',name+'.observed_fraction'])
    return np.asarray(result),names
