"""Render source-PTS audit frames, including frames with no detected skeleton."""
import gzip
import json
from PIL import Image,ImageDraw
from extract_exercise_quality_features import DATA,decoded_frames

EDGES=[(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28),(27,31),(28,32)]

def main():
    directory=DATA/'corpus-overlays';directory.mkdir(exist_ok=True)
    for path in sorted((DATA/'videos-v3').glob('subject_001_*.mp4')):
        cache=DATA/'world-feature-cache'/(path.stem+'.json.gz')
        with gzip.open(cache,'rt') as stream:payload=json.load(stream)
        rows={row['timestamp_ms']:row for row in payload['frames']}
        decoder=decoded_frames(path)
        try:
            for timestamp,pixels,_ in decoder:
                if timestamp<2000:continue
                row=rows[timestamp];image=Image.fromarray(pixels);draw=ImageDraw.Draw(image);width,height=image.size
                for a,b in EDGES if row['landmarks'] else []:
                    p,q=row['landmarks'][a],row['landmarks'][b]
                    color=(0,220,0) if min(p[3],p[4],q[3],q[4])>=0.65 else (110,110,110)
                    draw.line([(p[0]*width,p[1]*height),(q[0]*width,q[1]*height)],fill=color,width=2)
                draw.text((10,15),f'{path.stem} PTS {timestamp}ms',fill=(255,255,0))
                if not row['landmarks']:draw.text((10,35),'NO DETECTED POSE',fill=(255,80,80))
                image.save(directory/(path.stem+'.jpg'));break
        finally:decoder.close()
    exercises=['abs','back','bicep_curl','push_up','shoulder','squat','tricep']
    sheet=Image.new('RGB',(1470,1800),'#141414');draw=ImageDraw.Draw(sheet)
    for col,exercise in enumerate(exercises):
        for row,(quality,view) in enumerate([(q,v) for q in ['good','bad'] for v in ['front','side','diagonal']]):
            path=directory/f'subject_001_{exercise}_{quality}_{view}.jpg'
            if path.exists():
                image=Image.open(path);image.thumbnail((206,270));sheet.paste(image,(col*210+(210-image.width)//2,row*300+25))
            draw.text((col*210+3,row*300+4),f'{exercise} {quality} {view}',fill='white')
    sheet.save(DATA/'corpus-contact-sheet.jpg')
    print('Audit frames',len(list(directory.glob('*.jpg'))))

if __name__=='__main__':main()
