"""Original timestamped camera frames for the in-image evidence audit, no rep labels."""
import hashlib,json
from PIL import Image,ImageDraw,ImageFont
from extract_exercise_quality_features import ROOT,DATA,decoded_frames,POSE_MODELS
from extract_image_reference_candidates import read_cache,checksum


def main():
    output=DATA/'in-image-reference-original-frames.png';receipt=DATA/'in-image-reference-original-frames.json'
    if output.exists() or receipt.exists():raise FileExistsError('Preserve original-frame evidence')
    cases=[('subject_001_push_up_good_side',[1067,1200,6000],15),('subject_005_shoulder_good_front',[733,933,6000],16)]
    w,h=440,330;sheet=Image.new('RGB',(w*3,h*6),(20,20,20));font=ImageFont.load_default(size=12);records=[]
    for name,requested,wrist in cases:
        paths=[DATA/f'complete-reference-{m}-cache'/(name+'.json.gz') for m in ['full','heavy']]
        payloads=[read_cache(p) for p in paths];source=DATA/'videos-v3'/(name+'.mp4')
        if {p['source_video_sha256'] for p in payloads}!={checksum(source)}:raise ValueError('Original source mismatch')
        if [p['model_sha256'] for p in payloads]!=[POSE_MODELS[m][1] for m in ['full','heavy']]:raise ValueError('Wrong raw model identity')
        frames=[{r['timestamp_ms']:r for r in p['frames']} for p in payloads]
        times=[min(frames[0],key=lambda t:abs(t-v)) for v in requested];images={};decoder=decoded_frames(source,66,max(times)+100)
        try:
            for t,pixels,_ in decoder:
                if t in times:images[t]=pixels.copy()
        finally:decoder.close()
        for t in times:
            cells=[Image.fromarray(images[t]) for _ in range(3)];titles=['Original RGB','Pinned raw Full','Pinned raw Heavy'];measurements=[]
            for col,rows in enumerate(frames,1):
                points=rows[t]['landmarks'];draw=ImageDraw.Draw(cells[col])
                for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                    if not points:continue
                    visible=all(0<=points[i][0]<=1 and 0<=points[i][1]<=1 and min(points[i][3:5])>=.65 for i in [a,b])
                    draw.line([(points[a][0]*cells[col].width,points[a][1]*cells[col].height),(points[b][0]*cells[col].width,points[b][1]*cells[col].height)],fill=(0,230,100) if visible else (140,140,140),width=3)
                p=points[wrist] if points else None
                if p:titles[col]+=f' | wrist x={p[0]:.3f} y={p[1]:.3f}'
                measurements.append(dict(model=['full','heavy'][col-1],wrist_index=wrist,image_x=p[0] if p else None,image_y=p[1] if p else None,confidence=min(p[3:5]) if p else None))
            for col,cell in enumerate(cells):
                cell.thumbnail((w,h-60));sheet.paste(cell,(col*w+(w-cell.width)//2,len(records)*h+60));draw=ImageDraw.Draw(sheet)
                draw.text((col*w+4,len(records)*h+3),titles[col],font=font,fill='white');draw.text((col*w+4,len(records)*h+23),name,font=font,fill='white');draw.text((col*w+4,len(records)*h+41),f'Original PTS {t} ms',font=font,fill='white')
            records.append(dict(video=source.name,original_pts_ms=t,source_sha256=checksum(source),decoded_rgb_sha256=hashlib.sha256(images[t].tobytes()).hexdigest(),cache_sha256=[checksum(p) for p in paths],wrist_observations=measurements))
    sheet.save(output);receipt.write_text(json.dumps(dict(kind='original-image-admission-diagnostics-not-human-form-labels',observations=records,image_sha256=checksum(output),new_form_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n');print(output)


if __name__=='__main__':main()
