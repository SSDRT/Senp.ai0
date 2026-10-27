"""Original PTS RGB/Lite MP33 QA, including withheld raw detections; no labels."""
import hashlib
import json
from PIL import Image,ImageDraw,ImageFont
from extract_exercise_quality_features import ROOT,DATA,decoded_frames
from extract_image_reference_candidates import read_cache,checksum
from extract_lite_reference_candidate import SHA,PIPELINE


def main():
    image=DATA/'lite-reference-original-frames.png';report=DATA/'lite-reference-original-frames.json'
    if image.exists() or report.exists():raise FileExistsError('Preserve original timestamped QA')
    names=['subject_001_squat_good_front','subject_005_squat_good_side','subject_001_push_up_good_side']
    w,h=480,340;sheet=Image.new('RGB',(w*3,h*6),(20,20,20));font=ImageFont.load_default(size=13)
    observations=[];index=0
    for name in names:
        paths=[DATA/'complete-reference-full-cache'/(name+'.json.gz'),DATA/'lite-reference-cache'/(name+'.json.gz')]
        payloads=[read_cache(p) for p in paths];source=DATA/'videos-v3'/(name+'.mp4')
        if {p['source_video_sha256'] for p in payloads}!={checksum(source)}:raise ValueError('Wrong RGB source')
        if payloads[1]['model_sha256']!=SHA or payloads[1]['preprocessing_id']!=PIPELINE:raise ValueError('Wrong Lite identity')
        frames=[{r['timestamp_ms']:r for r in p['frames']} for p in payloads]
        times=[min(frames[0],key=lambda t:abs(t-requested)) for requested in (6000,10000)]
        images={};decoder=decoded_frames(source,66,max(times)+100)
        try:
            for t,pixels,_ in decoder:
                if t in times:images[t]=pixels.copy()
        finally:decoder.close()
        for t in times:
            input_sha=hashlib.sha256(images[t].tobytes()).hexdigest()
            if input_sha!=frames[1][t]['input_rgb_sha256']:raise ValueError('Actual original RGB differs from inferred frame')
            original=Image.fromarray(images[t]);cells=[original.copy() for _ in range(3)]
            titles=['Original RGB','Coarse Full VIDEO','Lite VIDEO'];sample=[f[t] for f in frames]
            for col,row in enumerate(sample,1):
                admitted=True;points=row['landmarks']
                if col==2:
                    admitted=row['subject_bound'];points=row['raw_input_landmarks']
                    titles[col]+=' / '+('bound' if admitted else 'withheld')
                if not points:
                    titles[col]+=' / no raw pose';continue
                draw=ImageDraw.Draw(cells[col]);confidence=[min(p[3:5]) for p in points]
                for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),
                    (23,25),(25,27),(24,26),(26,28),(27,29),(29,31),(28,30),(30,32)]:
                    color=(0,230,100) if admitted and min(confidence[a],confidence[b])>=.65 else (130,130,130)
                    draw.line([(points[a][0]*original.width,points[a][1]*original.height),
                        (points[b][0]*original.width,points[b][1]*original.height)],fill=color,width=3)
                for i,p in enumerate(points):
                    x,y=p[0]*original.width,p[1]*original.height
                    color=(255,225,0) if admitted and confidence[i]>=.65 else (130,130,130)
                    draw.ellipse((x-2,y-2,x+2,y+2),fill=color)
            for col,cell in enumerate(cells):
                cell.thumbnail((w,h-48));sheet.paste(cell,(col*w+(w-cell.width)//2,index*h+48))
                draw=ImageDraw.Draw(sheet)
                draw.text((col*w+5,index*h+4),f'{titles[col]} | original PTS {t} ms',font=font,fill='white')
                draw.text((col*w+5,index*h+24),name,font=font,fill='white')
            observations.append(dict(video=source.name,original_pts_ms=t,source_sha256=checksum(source),
                decoded_rgb_sha256=input_sha,inferred_rgb_hash_matches_original=True,
                cache_sha256=[checksum(p) for p in paths],lite_bound=sample[1]['subject_bound'],lite_raw_pose=bool(sample[1]['raw_input_landmarks'])))
            index+=1
    sheet.save(image)
    report.write_text(json.dumps(dict(kind='original-rgb-and-raw-model-diagnostics-not-new-form-labels',image=image.relative_to(ROOT).as_posix(),
        image_sha256=checksum(image),observations=observations,new_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n')
    print(image)


if __name__=='__main__':main()
