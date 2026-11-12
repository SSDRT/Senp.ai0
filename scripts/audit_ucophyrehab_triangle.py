"""Single-record coordinate/angle conformance; never rep or camera accuracy."""
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

from measured_triangle import triangle_angles

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT/'test-artifacts/exercise-knowledge/ucophyrehab-joint-audit-20261029'
PROTOCOL = ROOT/'models/ucophyrehab-triangle-audit-protocol.json'
JOINTS = ('l_hip', 'l_knee', 'l_ankle')
NUMBER = re.compile(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?', re.ASCII)


def recorded_number(value):
    if isinstance(value, bool): return None
    if isinstance(value, str):
        if not NUMBER.fullmatch(value.strip()): return None
        try: value = Decimal(value.strip())
        except InvalidOperation: return None
    if not isinstance(value, (int, float, Decimal)): return None
    try: return float(value) if math.isfinite(value) else None
    except (OverflowError, ValueError): return None


def coordinates(frame):
    result = []
    for joint in JOINTS:
        point = frame.get('joints', {}).get(joint)
        if not isinstance(point, dict) or set(point) != {'x','y','z'}: return None
        p = [recorded_number(point[axis]) for axis in ('x','y','z')]
        if any(v is None for v in p): return None
        result.append(p)
    return result


def summarize(frames):
    rows = []
    previous = -1
    for frame in frames:
        fid = frame.get('id')
        if type(fid) is not int or fid < 0 or fid <= previous:
            raise ValueError('Preserve original strictly increasing integer frame IDs')
        previous = fid
        points = coordinates(frame)
        angles = triangle_angles(points)
        target = recorded_number(frame.get('joints', {}).get('angle'))
        row = dict(source_frame_id=fid, recorded_angle_degrees=target)
        for i,joint in enumerate(JOINTS):
            value = angles[i] if angles else None
            row['triangle_at_'+joint] = value
            row['absolute_difference_at_'+joint] = abs(value-target) if value is not None and target is not None else None
        row['zero_coordinate_triplets'] = sum(p == [0,0,0] for p in points) if points else None
        rows.append(row)
    if not rows: raise ValueError('Do not substitute another recording')
    differences = {}
    for joint in JOINTS:
        values = sorted(r['absolute_difference_at_'+joint] for r in rows if r['absolute_difference_at_'+joint] is not None)
        differences[joint] = dict(compared_frames=len(values), mean_absolute_difference_degrees=statistics.mean(values) if values else None,
            maximum_absolute_difference_degrees=max(values) if values else None,
            p95_absolute_difference_degrees=values[math.ceil(.95*len(values))-1] if values else None)
    return rows, differences


def main():
    protocol = json.loads(PROTOCOL.read_bytes())
    source = DATA/'selected-joint-schema.json'
    if hashlib.sha256(source.read_bytes()).hexdigest() != protocol['selected_schema_sha256']:
        raise ValueError('Selected source changed')
    result = json.loads(source.read_bytes())
    if (result['selected_subject'], result['selected_exercise']) != (1,1): raise ValueError('Frozen selection changed')
    if (DATA/'triangle-conformance.json').exists(): raise FileExistsError('Preserve first measurement audit')
    rows, differences = summarize(result['frames'])
    with (DATA/'triangle-conformance.csv').open('x', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    report = dict(schema='ucophyrehab-triangle-conformance/1',kind='recorded-coordinate-angle-consistency-not-independent-accuracy',
        protocol_sha256=hashlib.sha256(PROTOCOL.read_bytes()).hexdigest(),selected_subject=1,selected_exercise=1,
        exercise_scope='left seated unsupported knee bending; not bodyweight_squat',selected_frames=len(rows),
        first_recorded_frame_id=rows[0]['source_frame_id'],last_recorded_frame_id=rows[-1]['source_frame_id'],
        missing_internal_frame_ids=rows[-1]['source_frame_id']-rows[0]['source_frame_id']+1-len(rows),
        supplied_frames_discarded=0,first_five_acquisition_frames_not_filled=True,
        explicit_timestamp_fields_in_selected_schema=[k for k in result['frame_schema'] if 'time' in k.casefold()],
        acquisition_clock_or_video_alignment_verified=False,timestamps_ms_invented=False,
        all_vertices_reported_without_selection=differences,original_zeros_retained=True,
        mean_or_max_angle_used_to_fit=False,tracked_confidence_inferred=False,anatomical_centres_verified=False,
        UCO_other_subject_trajectory_objects_constructed=False,UCO_other_subject_JSON_tokens_consumed=True,
        uiprmd_final_subjects_opened=False,ordinal_ratings_opened=False,new_human_labels=0,
        model_trained=False,production_changed=False,readiness_points_earned=0,human_live_rep_precision=None)
    (DATA/'triangle-conformance.json').write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    frames=result['frames'];indices=[0,len(frames)//2,len(frames)-1]
    fig=plt.figure(figsize=(15,5),layout='constrained')
    receipts=[]
    for column,index in enumerate(indices):
        frame=frames[index];points=coordinates(frame)
        if points is None: raise ValueError('Missing original snapshot; do not substitute another')
        ax=fig.add_subplot(1,3,column+1,projection='3d')
        p=np.asarray(points);ax.plot(*p.T,'o-',color='black',linewidth=1)
        for name,point in zip(JOINTS,p):ax.text(*point,name,fontsize=9)
        ax.set_xlabel('Recorded X (unit unaudited)');ax.set_ylabel('Recorded Y');ax.set_zlabel('Recorded Z')
        ax.set_box_aspect(np.maximum(np.ptp(p,axis=0),1e-9));ax.view_init(15,45)
        ax.set_title(f"Original frame ID {frame['id']} | time basis unverified",fontsize=10)
        receipts.append(dict(original_frame_ordinal=index,original_frame_id=frame['id'],original_points=frame['joints']))
    fig.suptitle('Recorded OptiTrack triplets: subject1 exercise1; no inferred points or RGB/camera result',fontsize=12)
    fig.savefig(DATA/'original-triplets.png',dpi=130);plt.close(fig)
    fig,ax=plt.subplots(figsize=(12,4),layout='constrained')
    ids=[r['source_frame_id'] for r in rows]
    ax.plot(ids,[r['recorded_angle_degrees'] for r in rows],color='black',linewidth=2,label='Recorded angle')
    for joint in JOINTS:ax.plot(ids,[r['triangle_at_'+joint] for r in rows],linewidth=.8,label='Triangle at '+joint)
    ax.set_xlabel('Original frame ID (not milliseconds)');ax.set_ylabel('Degrees');ax.legend(ncol=2)
    ax.set_title('All three vertices retained; coordinate/label arithmetic agreement is not pose accuracy')
    fig.savefig(DATA/'all-vertex-trace.png',dpi=130);plt.close(fig)
    (DATA/'original-frame-receipts.json').write_text(json.dumps(dict(frames=receipts,selection='first/middle/last supplied frame, frozen before numerical interpretation'),indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(report,indent=2))


if __name__ == '__main__': main()
