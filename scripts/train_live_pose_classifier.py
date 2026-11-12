"""Fit a compact downstream classifier from Google's Apache-2.0 pose sample CSV.

This trains exercise/state prototypes, NOT landmark-network weights or form correctness.
The split groups identical image names; subject/video identities are unavailable upstream.
"""
import argparse
import base64
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BLOB = '136d951100d04926975f5eff4eab7091a409a002'
SHA = 'eb3a54230084616caf7505683692d873968f406b52593a0d699b13d855afc729'
URL = 'https://api.github.com/repos/googlesamples/mlkit/git/blobs/' + BLOB
TRIPLETS = [(13,11,23),(14,12,24),(11,13,15),(12,14,16),(11,23,25),(12,24,26),
            (23,25,27),(24,26,28),(25,27,31),(26,28,32),(11,23,27),(12,24,28)]
PAIRS = [(15,16),(27,28),(25,26),(13,14),(15,11),(16,12),(15,23),(16,24)]

def features(points):
    def sub(a,b): return [x-y for x,y in zip(a,b)]
    def norm(a): return math.sqrt(sum(x*x for x in a))
    result = []
    for a,b,c in TRIPLETS:
        u,v = sub(points[a],points[b]),sub(points[c],points[b])
        denominator = norm(u)*norm(v)
        if denominator <= 1e-8: return None
        result.append(math.acos(max(-1,min(1,sum(x*y for x,y in zip(u,v))/denominator)))/math.pi)
    torso = norm(sub([(x+y)/2 for x,y in zip(points[11],points[12])],
                     [(x+y)/2 for x,y in zip(points[23],points[24])]))
    if torso <= 1e-8: return None
    return result + [norm(sub(points[a],points[b]))/torso for a,b in PAIRS]

def distance(a,b): return sum((x-y)**2 for x,y in zip(a,b))/len(a)
def quantile(values,q):
    values=sorted(values); position=(len(values)-1)*q; lo=int(position); hi=math.ceil(position)
    return values[lo]*(hi-position)+values[hi]*(position-lo) if hi!=lo else values[lo]

def fit(rows):
    mean=[sum(r['features'][j] for r in rows)/len(rows) for j in range(20)]
    scale=[max(0.05,math.sqrt(sum((r['features'][j]-mean[j])**2 for r in rows)/len(rows))) for j in range(20)]
    def standardize(values): return [max(-8,min(8,(x-m)/s)) for x,m,s in zip(values,mean,scale)]
    classes=[]
    for label in sorted({r['label'] for r in rows}):
        samples=[standardize(r['features']) for r in rows if r['label']==label]
        rng=random.Random(20261022)
        centers=[samples[rng.randrange(len(samples))]]
        while len(centers)<12:
            weights=[min(distance(x,c) for c in centers) for x in samples]
            centers.append(samples[rng.choices(range(len(samples)),weights=weights)[0]])
        for _ in range(35):
            groups=[[] for _ in centers]
            for sample in samples: groups[min(range(len(centers)),key=lambda i:distance(sample,centers[i]))].append(sample)
            centers=[[sum(x[j] for x in group)/len(group) for j in range(20)] if group else old
                     for old,group in zip(centers,groups)]
        radius=max(0.10,quantile([math.sqrt(min(distance(x,c) for c in centers)) for x in samples],0.95))
        classes.append({'label':label,'exercise_id':'push_up' if label.startswith('pushups') else 'bodyweight_squat',
                        'state':label.split('_')[-1],'radius':radius,'centers':centers})
    return {'schema':'sample-pose-classifier/1','feature_schema':'mp33-image-3d-angles-distances/1',
            'source_sha256':SHA,'mean':mean,'scale':scale,'classes':classes,
            'maximum_normalized_distance':1.0,'minimum_class_margin':0.10,
            'deployment':'opt-in auxiliary classifier; no automatic technique prescription'}

def predict(model,values):
    v=[max(-8,min(8,(x-m)/s)) for x,m,s in zip(values,model['mean'],model['scale'])]
    ranked=sorted((math.sqrt(min(distance(v,c) for c in cls['centers'])),cls['label'],cls['radius']) for cls in model['classes'])
    best=ranked[0]; normalized=best[0]/best[2]; margin=(ranked[1][0]-best[0])/max(ranked[1][0],1e-8)
    accepted=normalized<=model['maximum_normalized_distance'] and margin>=model['minimum_class_margin']
    return {'label':best[1] if accepted else None,'nearest_label':best[1],'normalized_distance':normalized,'margin':margin}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=ROOT/'test-artifacts/exercise-knowledge/fitness_pose_samples.csv')
    parser.add_argument('--fetch',action='store_true')
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'core-motion/src/main/resources/ai/senp/motion/sample-pose-classifier.json')
    parser.add_argument('--report',type=Path,default=ROOT/'test-artifacts/exercise-knowledge/classifier-training-report.json')
    args=parser.parse_args()
    if not args.input.exists() and args.fetch:
        request=urllib.request.Request(URL,headers={'User-Agent':'Senpai-pose-training'})
        data=json.load(urllib.request.urlopen(request,timeout=30))
        args.input.parent.mkdir(parents=True,exist_ok=True);args.input.write_bytes(base64.b64decode(data['content']))
    raw=args.input.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SHA: raise ValueError('Pinned training CSV SHA-256 mismatch')
    rows=[]
    for name,label,*coordinates in csv.reader(raw.decode().splitlines()):
        if len(coordinates)!=99: raise ValueError('Expected all 33 XYZ landmarks')
        values=list(map(float,coordinates))
        if not all(math.isfinite(v) for v in values): raise ValueError('Nonfinite source landmark')
        points=[values[i:i+3] for i in range(0,99,3)]; vector=features(points)
        if vector is None: continue
        rows.append({'name':name,'label':label,'features':vector,'points':points})
    groups=sorted({r['name'] for r in rows});random.Random(20261022).shuffle(groups)
    test_groups=set(groups[:max(1,len(groups)//5)])
    train=[r for r in rows if r['name'] not in test_groups];test=[r for r in rows if r['name'] in test_groups]
    assert not {r['name'] for r in train}&{r['name'] for r in test}
    model=fit(train)
    # Quantize exported numbers before evaluation so the report describes the delivered model.
    model=json.loads(json.dumps(model),parse_float=lambda s:round(float(s),8))
    results=[(r,predict(model,r['features'])) for r in test]
    accepted=[(r,p) for r,p in results if p['label'] is not None]
    correct=sum(p['label']==r['label'] for r,p in accepted)
    confusion=collections.Counter((r['label'],p['label'] or 'UNKNOWN') for r,p in results)
    report={'kind':'real-upstream-landmark-sample-exercise-state-classifier-not-form-or-landmark-accuracy',
            'source':URL,'source_blob':BLOB,'source_sha256':SHA,'license':'Apache-2.0 (upstream repository)',
            'source_samples':len(rows),'training_samples':len(train),'holdout_samples':len(test),
            'split':'image-name grouped deterministic 80/20; not subject/video-independent',
            'seed':20261022,'class_counts':dict(collections.Counter(r['label'] for r in rows)),
            'prototype_count':sum(len(c['centers']) for c in model['classes']),
            'holdout_coverage':len(accepted)/len(test),'accepted_state_accuracy':correct/len(accepted) if accepted else None,
            'all_holdout_accuracy_with_abstentions_as_misses':correct/len(test),
            'closed_set_nearest_state_accuracy':sum(p['nearest_label']==r['label'] for r,p in results)/len(test),
            'confusion':[{'actual':a,'predicted':p,'count':n} for (a,p),n in sorted(confusion.items())],
            'limits':['No subject/video IDs; neighbouring or same-subject images may cross the split.',
                      'No expert form labels or independent landmark truth.',
                      'Only squat/push-up terminal states; no labelled negative/OOD classes.',
                      'Upstream source model and current MediaPipe image landmarks may differ.',
                      'Do not enable automatic coaching from these metrics alone.']}
    output=json.dumps(model,indent=2,sort_keys=True)+'\n'
    parity=[{k:r[k] for k in ('name','label','points','features')}|{'prediction':p} for r,p in results[:16]]
    parity_path=ROOT/'core-motion/src/test/resources/ai/senp/motion/sample-classifier-parity.json'
    if args.check:
        assert args.output.read_text()==output,'Classifier artifact is stale'
        assert json.loads(parity_path.read_text())==parity,'Classifier parity fixture is stale'
    else:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(output,newline='\n')
        parity_path.parent.mkdir(parents=True,exist_ok=True);parity_path.write_text(json.dumps(parity,indent=2)+'\n',newline='\n')
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('source_samples','training_samples','holdout_samples','holdout_coverage','accepted_state_accuracy','closed_set_nearest_state_accuracy')},indent=2))

if __name__=='__main__':main()
