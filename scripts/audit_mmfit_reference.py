"""Explain a frozen activity proposal failure without changing gates or selecting a new bout."""
from collections import Counter
import gzip
import json
import math

from extract_mmfit_reference import DATA, REQUIRED, SOURCE_SHA, activity_proposal, angle, unit, minus, dot, sha


def audit(rows):
    counts=Counter();hits=[];confidence=Counter()
    for row in rows:
        counts['original_observations']+=1
        points=row['landmarks']
        if len(points)!=33:continue
        counts['raw_mp33_present']+=1
        for i in REQUIRED:
            if min(points[i][3:5])>=.65:confidence[str(i)]+=1
        if any(len(p)!=8 or not all(math.isfinite(v) for v in p) for p in points):continue
        counts['all_raw_values_finite']+=1
        if any(min(points[i][3:5])<.65 for i in REQUIRED):continue
        counts['all_required_confident']+=1
        if any(not (0<=points[i][0]<=1 and 0<=points[i][1]<=1) for i in REQUIRED):continue
        counts['all_required_on_image']+=1
        world=[p[5:8] for p in points]
        lateral=unit(minus(world[12],world[11]));vertical=unit([world[11][i]+world[12][i]-world[23][i]-world[24][i] for i in range(3)])
        if lateral is None or vertical is None:continue
        normal=unit([lateral[1]*vertical[2]-lateral[2]*vertical[1],lateral[2]*vertical[0]-lateral[0]*vertical[2],lateral[0]*vertical[1]-lateral[1]*vertical[0]])
        if normal is None:continue
        tests=dict(elbow=True,shoulder=True,knee=True,lateral=True,plane=True)
        for s,e,w,h,k,a in [(11,13,15,23,25,27),(12,14,16,24,26,28)]:
            elbow=angle(world[s],world[e],world[w]);shoulder=angle(world[e],world[s],world[h]);knee=angle(world[h],world[k],world[a]);upper=unit(minus(world[e],world[s]))
            tests['elbow'] &= elbow is not None and elbow>=140
            tests['shoulder'] &= shoulder is not None and 35<=shoulder<=110
            tests['knee'] &= knee is not None and 80<=knee<=135
            tests['lateral'] &= upper is not None and abs(dot(upper,lateral))>=.60
            tests['plane'] &= upper is not None and abs(dot(upper,normal))<=.45
        for name,value in tests.items():counts[name+'_passes_independently_after_confidence']+=int(value)
        admitted=all(tests.values())
        if admitted!=activity_proposal(points):raise ValueError('Diagnostic disagrees with frozen activity proposal')
        if admitted:hits.append(row['timestamp_ms'])
    return dict(counts=dict(counts),required_landmark_confident_observations=dict(confidence),proposal_hit_pts_ms=hits)


if __name__=='__main__':
    cache=DATA/'coarse.json.gz'
    with gzip.open(cache,'rt') as stream:payload=json.load(stream)
    if payload['source_video_sha256']!=SOURCE_SHA:raise ValueError('Wrong frozen recording')
    result=dict(schema='mmfit-activity-proposal-diagnostics/1',cache_sha256=sha(cache),
        no_gate_changes=True,no_new_bout_selected=True,independent_form_precision=None,**audit(payload['frames']))
    destination=DATA/'proposal-diagnostics.json'
    if destination.exists():raise FileExistsError('Preserve diagnostic evidence')
    destination.write_text(json.dumps(result,indent=2)+'\n',newline='\n')
    print(json.dumps(result,indent=2))
