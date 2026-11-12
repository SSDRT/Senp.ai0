"""Original timestamped RGB and raw control/CLAHE poses; no new exercise labels."""
import hashlib,json
from PIL import Image,ImageDraw,ImageFont
from extract_exercise_quality_features import ROOT,DATA,decoded_frames
from extract_image_reference_candidates import read_cache,checksum
from extract_clahe_reference_candidate import SHA,PIPELINE,transform_rgb


def main():
    image=DATA/'clahe-reference-original-frames.png';report=DATA/'clahe-reference-original-frames.json'
    if image.exists() or report.exists():raise FileExistsError('Preserve original-frame review')
    names=['subject_001_squat_good_front','subject_005_squat_good_side','subject_001_push_up_good_side']
    w,h=400,310;sheet=Image.new('RGB',(w*4,h*6),(20,20,20));font=ImageFont.load_default(size=12);records=[]
    for name in names:
        paths=[DATA/d/(name+'.json.gz') for d in ['image-reference-full_image-cache','clahe-reference-cache']]
        payloads=[read_cache(p) for p in paths];source=DATA/'videos-v3'/(name+'.mp4')
        if {p['source_video_sha256'] for p in payloads}!={checksum(source)}:raise ValueError('Wrong original RGB source')
        if payloads[1]['model_sha256']!=SHA or payloads[1]['preprocessing_id']!=PIPELINE:raise ValueError('Wrong photometric pipeline')
        frames=[{r['timestamp_ms']:r for r in p['frames']} for p in payloads]
        times=[min(frames[0],key=lambda t:abs(t-requested)) for requested in (6000,10000)]
        original_frames={};decoder=decoded_frames(source,66,max(times)+100)
        try:
            for t,pixels,_ in decoder:
                if t in times:original_frames[t]=pixels.copy()
        finally:decoder.close()
        for t in times:
            original=original_frames[t];filtered=transform_rgb(original);a,b=[f[t] for f in frames]
            original_sha=hashlib.sha256(original.tobytes()).hexdigest();input_sha=hashlib.sha256(filtered.tobytes()).hexdigest()
            if original_sha!=b['original_rgb_sha256'] or input_sha!=b['input_rgb_sha256']:raise ValueError('Original or inferred RGB identity differs')
            if a.get('input_rgb_sha256') not in (None,original_sha):raise ValueError('Control RGB identity differs')
            cells=[Image.fromarray(original),Image.fromarray(filtered),Image.fromarray(original),Image.fromarray(original)]
            titles=['Original RGB','Frozen LAB CLAHE RGB','Original Full IMAGE','CLAHE Full IMAGE']
            for col,row in [(2,a),(3,b)]:
                bound=row['subject_bound'];points=row['raw_input_landmarks'];titles[col]+=' / '+('bound' if bound else 'withheld')
                draw=ImageDraw.Draw(cells[col])
                for u,v in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                    if not points:continue
                    colour=(0,230,100) if bound and min(*points[u][3:5],*points[v][3:5])>=.65 else (130,130,130)
                    draw.line([(points[u][0]*cells[col].width,points[u][1]*cells[col].height),(points[v][0]*cells[col].width,points[v][1]*cells[col].height)],fill=colour,width=3)
            for col,cell in enumerate(cells):
                cell.thumbnail((w,h-48));sheet.paste(cell,(col*w+(w-cell.width)//2,len(records)*h+48));draw=ImageDraw.Draw(sheet)
                draw.text((col*w+4,len(records)*h+4),f'{titles[col]} | PTS {t} ms',font=font,fill='white');draw.text((col*w+4,len(records)*h+24),name,font=font,fill='white')
            records.append(dict(video=source.name,original_pts_ms=t,source_sha256=checksum(source),original_rgb_sha256=original_sha,inferred_rgb_sha256=input_sha,cache_sha256=[checksum(p) for p in paths],original_image_bound=a['subject_bound'],clahe_image_bound=b['subject_bound']))
    sheet.save(image);report.write_text(json.dumps(dict(kind='original-rgb-and-raw-photometric-model-QA-not-human-rep-labels',image_sha256=checksum(image),observations=records,new_rep_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n');print(image)


if __name__=='__main__':main()
