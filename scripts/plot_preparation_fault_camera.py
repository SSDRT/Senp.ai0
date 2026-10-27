"""Original RGB and existing raw MP33 for changed replay examples; no rep labels."""
import gzip
import hashlib
import json
from PIL import Image,ImageDraw,ImageFont
from extract_exercise_quality_features import ROOT,DATA,decoded_frames


def main():
    prefix='preparation-fault'
    output=DATA/(prefix+'-original-camera.png');receipt=DATA/(prefix+'-original-camera.json')
    if output.exists() or receipt.exists():raise FileExistsError('Preserve camera review')
    names=['subject_001_shoulder_good_front']
    times={400,667,867,1133,1467,1800}
    expected=len(names)*len(times)
    sheet=Image.new('RGB',(1200,expected*265),(20,20,20));font=ImageFont.load_default(size=13);records=[]
    for name in names:
        caches=[DATA/d/(name+'.json.gz') for d in ['rep-world-feature-cache','autonomous-heavy-cache']]
        payloads=[]
        for cache in caches:
            with gzip.open(cache,'rt') as stream:payloads.append(json.load(stream))
        source=DATA/'videos-v3'/(name+'.mp4');source_sha=hashlib.sha256(source.read_bytes()).hexdigest()
        if {p['source_video_sha256'] for p in payloads}!={source_sha}:raise ValueError('Original video/cache mismatch')
        rows=[{r['timestamp_ms']:r for r in p['frames']} for p in payloads]
        decoder=decoded_frames(source,66,max(times)+100)
        try:
            for t,pixels,_ in decoder:
                if t not in times:continue
                images=[Image.fromarray(pixels) for _ in range(3)]
                for col,cache_rows in enumerate(rows,1):
                    points=cache_rows[t]['landmarks'];draw=ImageDraw.Draw(images[col])
                    for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                        if not points:continue
                        color=(0,235,100) if min(*points[a][3:5],*points[b][3:5])>=.65 else (140,140,140)
                        draw.line([(points[a][0]*images[col].width,points[a][1]*images[col].height),
                                   (points[b][0]*images[col].width,points[b][1]*images[col].height)],fill=color,width=3)
                for col,(image,title) in enumerate(zip(images,['Original RGB','Pinned raw Full','Pinned raw Heavy'])):
                    image.thumbnail((400,220));sheet.paste(image,(400*col,265*len(records)+45))
                    draw=ImageDraw.Draw(sheet);draw.text((400*col+4,265*len(records)+4),f'{title} | original PTS {t}ms',font=font,fill='white')
                    draw.text((400*col+4,265*len(records)+23),name,font=font,fill='white')
                records.append(dict(video=source.name,source_sha256=source_sha,original_pts_ms=t,
                    rgb_sha256=hashlib.sha256(pixels.tobytes()).hexdigest(),cache_sha256=[hashlib.sha256(p.read_bytes()).hexdigest() for p in caches]))
        finally:decoder.close()
    if len(records)!=expected:raise ValueError('Missing original RGB timestamps')
    sheet.save(output)
    receipt.write_text(json.dumps(dict(schema='ready-form-original-camera-qa/1',observations=records,
        image_sha256=hashlib.sha256(output.read_bytes()).hexdigest(),new_rep_labels=0),indent=2)+'\n',newline='\n')
    print(output)


if __name__=='__main__':main()
