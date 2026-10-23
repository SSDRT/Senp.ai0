"""Extract confidence-masked world geometry with pinned MediaPipe Full/Heavy 0.10.32.

Keeps all 33 image/world landmarks, visibility/presence and actual video timestamps
in ignored per-video caches. Dataset labels describe clips, not individual faults.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import queue
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge'
MODEL=ROOT/'build/generated/pose-model-assets/pose_landmarker_full.task'
MODEL_SHA='5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1'
POSE_MODELS={'full':(MODEL,MODEL_SHA),
             'heavy':(ROOT/'build/generated/heavy-pose-model-assets/pose_landmarker_heavy.task',
                      '64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b')}
SCHEMA='mp33-world-technique-features/1'
TRIPLETS={'left_shoulder':(13,11,23),'right_shoulder':(14,12,24),'left_elbow':(11,13,15),'right_elbow':(12,14,16),
          'left_hip':(11,23,25),'right_hip':(12,24,26),'left_knee':(23,25,27),'right_knee':(24,26,28),
          'left_ankle':(25,27,31),'right_ankle':(26,28,32),'left_body_line':(11,23,27),'right_body_line':(12,24,28)}
PAIRS={'hand_separation':(15,16),'foot_separation':(27,28),'knee_separation':(25,26),'elbow_separation':(13,14),
       'left_hand_to_shoulder':(15,11),'right_hand_to_shoulder':(16,12),'left_hand_to_hip':(15,23),'right_hand_to_hip':(16,24),
       'left_elbow_to_hip':(13,23),'right_elbow_to_hip':(14,24)}
RELATIONS=['hand_width_over_shoulders','foot_width_over_hips','knee_width_over_feet']
FEATURES=sorted(['angle.'+k for k in TRIPLETS]+['distance.'+k for k in PAIRS]+['relation.'+k for k in RELATIONS])
FILENAME=re.compile(r'subject_(\d+)_(abs|back|bicep_curl|bicep|push_up|shoulder|squat|tricep)_(good|bad)_(front|side|diagonal)\.mp4')

def geometry(points,confidence):
    def minus(a,b):return [x-y for x,y in zip(a,b)]
    def norm(p):return math.sqrt(sum(v*v for v in p))
    def reliable(indices):return min(confidence[i] for i in indices)>=0.65
    result={}
    for name,(a,b,c) in TRIPLETS.items():
        if not reliable((a,b,c)):continue
        u,v=minus(points[a],points[b]),minus(points[c],points[b]);denom=norm(u)*norm(v)
        if denom>1e-8:result['angle.'+name]=math.degrees(math.acos(max(-1,min(1,sum(x*y for x,y in zip(u,v))/denom))))
    anchors=(11,12,23,24)
    torso=norm(minus([(points[11][i]+points[12][i])/2 for i in range(3)],[(points[23][i]+points[24][i])/2 for i in range(3)]))
    if torso>1e-6 and reliable(anchors):
        for name,(a,b) in PAIRS.items():
            if reliable((a,b)):result['distance.'+name]=norm(minus(points[a],points[b]))/torso
        ratios={'shoulder':norm(minus(points[11],points[12]))/torso,'hip':norm(minus(points[23],points[24]))/torso}
        for name,top,bottom in [('hand_width_over_shoulders',result.get('distance.hand_separation'),ratios['shoulder']),
                              ('foot_width_over_hips',result.get('distance.foot_separation'),ratios['hip']),
                              ('knee_width_over_feet',result.get('distance.knee_separation'),result.get('distance.foot_separation'))]:
            if top is not None and bottom is not None and bottom>=0.05:result['relation.'+name]=top/bottom
    return [result.get(k) for k in FEATURES]

def decoded_frames(path,sample_period_ms=500):
    """FFmpeg decode with actual selected source PTS, never synthetic FPS ticks."""
    import numpy as np
    metadata=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
                      '-show_streams','-show_format','-of','json',str(path)],text=True))
    stream=metadata['streams'][0];width,height=int(stream['width']),int(stream['height'])
    rotation=next((float(s['rotation']) for s in stream.get('side_data_list',[]) if 'rotation' in s),0)
    if round(rotation)%180:width,height=height,width
    factor=min(1,960/max(width,height));width=max(2,round(width*factor/2)*2);height=max(2,round(height*factor/2)*2)
    duration_ms=float(stream.get('duration',metadata['format']['duration']))*1000
    command=['ffmpeg','-hide_banner','-loglevel','info','-threads','1','-i',str(path),'-t','24',
             '-an','-sn','-dn','-vf',f"select='isnan(prev_selected_t)+gte(t-prev_selected_t,{sample_period_ms/1000})',scale={width}:{height},showinfo",
             '-fps_mode','passthrough','-filter_threads','1','-pix_fmt','rgb24','-f','rawvideo','pipe:1']
    decoder=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    timestamps=queue.Queue();errors=[]
    def stderr_reader():
        for raw in decoder.stderr:
            line=raw.decode(errors='replace')
            found=re.search(r'\bn:\s*\d+\s+pts:\s*[-\d]+\s+pts_time:([-\d.e+]+)',line)
            if found:timestamps.put(round(float(found[1])*1000))
            elif 'error' in line.lower():errors.append(line.strip())
    reader=threading.Thread(target=stderr_reader,daemon=True);reader.start()
    try:
        size=width*height*3
        while True:
            chunks=bytearray()
            while len(chunks)<size:
                chunk=decoder.stdout.read(size-len(chunks))
                if not chunk:break
                chunks.extend(chunk)
            if not chunks:break
            if len(chunks)!=size:raise ValueError('Truncated decoded frame')
            timestamp=timestamps.get(timeout=30)
            yield timestamp,np.frombuffer(chunks,dtype=np.uint8).reshape(height,width,3),duration_ms
        if decoder.wait(timeout=30)!=0:raise ValueError('FFmpeg decode failed: '+'; '.join(errors[-3:]))
    finally:
        if decoder.poll() is None:decoder.kill()
        decoder.wait();reader.join(timeout=5);decoder.stdout.close();decoder.stderr.close()

def process_video(path_string,sample_period_ms=500,cache_directory=None,model_variant='full'):
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    from PIL import Image,ImageDraw
    path=Path(path_string);match=FILENAME.fullmatch(path.name)
    if not match:raise ValueError('Unexpected labelled video name')
    if model_variant not in POSE_MODELS:raise ValueError('Unknown pinned pose model')
    model_path,model_sha=POSE_MODELS[model_variant]
    if hashlib.sha256(model_path.read_bytes()).hexdigest()!=model_sha:raise ValueError('Pinned pose model hash mismatch')
    default_directory='world-feature-cache' if model_variant=='full' else 'world-feature-cache-heavy'
    cache=(Path(cache_directory) if cache_directory else DATA/default_directory)/(path.stem+'.json.gz')
    if cache.exists():
        with gzip.open(cache,'rt') as stream:existing=json.load(stream)
        if existing.get('schema')==SCHEMA and existing.get('model_sha256')==model_sha and existing.get('sample_period_ms')==sample_period_ms and existing.get('source_video_sha256')==hashlib.sha256(path.read_bytes()).hexdigest():return path.name,'cached'
    options=vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(model_path)),
              running_mode=vision.RunningMode.VIDEO,num_poses=1,min_pose_detection_confidence=0.5,min_pose_presence_confidence=0.5,min_tracking_confidence=0.5)
    observations=[];last=-1;sample_period=sample_period_ms;duration_ms=None
    decoded=decoded_frames(path,sample_period_ms)
    try:
        with vision.PoseLandmarker.create_from_options(options) as estimator:
            for timestamp,image,duration_ms in decoded:
                if timestamp<=last:continue
                last=timestamp
                height,width=image.shape[:2]
                inference_start=time.perf_counter()
                result=estimator.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,data=image),timestamp)
                inference_ms=(time.perf_counter()-inference_start)*1000
                row={'timestamp_ms':timestamp,'image_width':width,'image_height':height,'features':[None]*25,'landmarks':[],
                     'research_inference_ms':inference_ms}
                if result.pose_landmarks and result.pose_world_landmarks and len(result.pose_landmarks[0])==33 and len(result.pose_world_landmarks[0])==33:
                    image_points=result.pose_landmarks[0];world=result.pose_world_landmarks[0]
                    confidence=[min(p.visibility or 0,p.presence or 0) for p in image_points]
                    row['features']=geometry([[p.x,p.y,p.z] for p in world],confidence)
                    row['landmarks']=[[p.x,p.y,p.z,p.visibility,p.presence,w.x,w.y,w.z] for p,w in zip(image_points,world)]
                    if model_variant=='full' and sample_period_ms==500 and match[1]=='001' and 2000<=timestamp<2500:
                        overlay=Image.fromarray(image);draw=ImageDraw.Draw(overlay)
                        for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28),(27,31),(28,32)]:
                            p,q=image_points[a],image_points[b]
                            color=(0,220,0) if min(confidence[a],confidence[b])>=0.65 else (110,110,110)
                            draw.line([(round(p.x*width),round(p.y*height)),(round(q.x*width),round(q.y*height))],fill=color,width=2)
                        draw.text((10,15),path.stem+' PTS '+str(timestamp)+'ms',fill=(255,255,0))
                        audit=DATA/'corpus-overlays';audit.mkdir(exist_ok=True)
                        overlay.save(audit/(path.stem+'.jpg'))
                observations.append(row)
    finally:decoded.close()
    if not observations:raise ValueError('No decodable frames: '+path.name)
    payload={'schema':SCHEMA,'video':path.name,'subject':match[1],'exercise':'bicep_curl' if match[2]=='bicep' else match[2],'quality':match[3],'view':match[4],
             'source_video_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'model_sha256':model_sha,
             'runtime':f'mediapipe 0.10.32 {model_variant} CPU Python; FFmpeg source-PTS RGB decoder; optional drawing exports disabled','sample_period_ms':sample_period,'maximum_sampled_duration_ms':24000,
             'source_duration_ms':duration_ms,'feature_names':FEATURES,'frames':observations}
    cache.parent.mkdir(parents=True,exist_ok=True);temporary=cache.with_suffix('.partial')
    with gzip.open(temporary,'wt',encoding='utf-8') as output:json.dump(payload,output,separators=(',',':'),allow_nan=False)
    temporary.replace(cache)
    return path.name,len(observations)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--watch',action='store_true');parser.add_argument('--workers',type=int,default=3)
    args=parser.parse_args()
    if hashlib.sha256(MODEL.read_bytes()).hexdigest()!=MODEL_SHA:raise ValueError('Pinned Full model hash mismatch')
    directory=DATA/'world-feature-cache';directory.mkdir(parents=True,exist_ok=True)
    submitted={p.name for p in directory.glob('*.json.gz')};submitted={s.replace('.json.gz','.mp4') for s in submitted}
    completed=len(submitted);start=time.monotonic();last_update=start;futures={};failures=[]
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        while True:
            pending=[p for p in (DATA/'videos-v3').glob('*.mp4') if p.name not in submitted]
            for path in sorted(pending)[:max(0,args.workers*3-len(futures))]:
                submitted.add(path.name);futures[pool.submit(process_video,str(path))]=path.name
            done=[f for f in futures if f.done()]
            for future in done:
                name=futures.pop(future)
                try:future.result();completed+=1
                except Exception as error:failures.append({'file':name,'error':repr(error)})
                last_update=time.monotonic()
                if completed%20==0:print(f'EXTRACT completed {completed}/1042, failed {len(failures)}, elapsed {time.monotonic()-start:.0f}s',flush=True)
            if completed+len(failures)>=1042:break
            if not futures and not pending and (not args.watch or time.monotonic()-last_update>180):break
            time.sleep(0.5)
    report={'schema':SCHEMA,'expected_videos':1042,'completed_videos':completed,'failures':failures,'complete':completed==1042 and not failures,'seconds':time.monotonic()-start}
    (DATA/'corpus-extraction-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('EXTRACT final',json.dumps(report),flush=True)

if __name__=='__main__':main()
