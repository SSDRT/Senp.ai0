"""Original PTS frames for every changed source run; no new correctness labels."""
import hashlib,json
from PIL import Image,ImageDraw,ImageFont
from extract_exercise_quality_features import DATA,decoded_frames,POSE_MODELS
from extract_image_reference_candidates import read_cache,checksum


def main():
    receipt=DATA/'in-image-source-original-frames.json'
    if receipt.exists():raise FileExistsError('Preserve source frame evidence')
    comparison=json.loads((DATA/'in-image-source-comparison.json').read_bytes());records=[];sheets=[];font=ImageFont.load_default(size=12);w,h=440,330
    for run in comparison['changed_runs']:
        name=run['video'];stem=name.removesuffix('.mp4')+'-'+run['side'].lower();base=DATA/'in-image-source-screen'
        a=json.loads((base/(stem+'-original.json')).read_bytes());b=json.loads((base/(stem+'-in_image.json')).read_bytes())
        times=[x['timestamp_ms'] for x,y in zip(a['trace'],b['trace']) if x!=y]
        assert times
        chosen=[times[0],times[len(times)//2],times[-1]]
        # Include the middle of the first changed non-qualified attempt, when present.
        aa={(x['started_ms'],x['completed_ms'],x['verdict']) for x in run['original_attempts']}
        changed=[x for x in run['masked_attempts'] if (x['started_ms'],x['completed_ms'],x['verdict']) not in aa and x['verdict'] not in {'CORRECT','ACCEPTABLE'}]
        if changed:chosen[1]=(changed[0]['started_ms']+changed[0]['completed_ms'])//2
        paths=[DATA/folder/(name.replace('.mp4','.json.gz')) for folder in ['rep-world-feature-cache','autonomous-heavy-cache']]
        payloads=[read_cache(p) for p in paths];source=DATA/'videos-v3'/name
        assert {x['source_video_sha256'] for x in payloads}=={checksum(source)}
        assert [x['model_sha256'] for x in payloads]==[POSE_MODELS[m][1] for m in ['full','heavy']]
        rows=[{x['timestamp_ms']:x for x in p['frames']} for p in payloads];chosen=[min(rows[0],key=lambda t:abs(t-v)) for v in chosen];images={};decoder=decoded_frames(source,66,max(chosen)+100)
        try:
            for t,pixels,_ in decoder:
                if t in chosen:images[t]=pixels.copy()
        finally:decoder.close()
        for t in chosen:
            if len(records)%6==0:sheets.append(Image.new('RGB',(w*3,h*6),(20,20,20)))
            sheet=sheets[-1];y=(len(records)%6)*h;panels=[Image.fromarray(images[t]) for _ in range(3)];titles=['Original RGB','Pinned raw Full','Pinned raw Heavy'];outside=[]
            for col,cache in enumerate(rows,1):
                points=cache[t]['landmarks'];draw=ImageDraw.Draw(panels[col]);missing=[]
                for j,p in enumerate(points):
                    if min(p[3:5])>=.65 and not(0<=p[0]<=1 and 0<=p[1]<=1):missing.append(j)
                outside.append(dict(model=['full','heavy'][col-1],high_confidence_outside_landmark_indices=missing))
                for k,l in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                    if not points:continue
                    seen=all(min(points[j][3:5])>=.65 and 0<=points[j][0]<=1 and 0<=points[j][1]<=1 for j in [k,l])
                    draw.line([(points[k][0]*panels[col].width,points[k][1]*panels[col].height),(points[l][0]*panels[col].width,points[l][1]*panels[col].height)],fill=(0,230,100) if seen else (145,145,145),width=3)
                titles[col]+=' | outside: '+','.join(map(str,missing))
            for col,panel in enumerate(panels):
                panel.thumbnail((w,h-60));sheet.paste(panel,(col*w+(w-panel.width)//2,y+60));draw=ImageDraw.Draw(sheet)
                draw.text((col*w+4,y+3),titles[col],font=font,fill='white');draw.text((col*w+4,y+23),name+' '+run['side'],font=font,fill='white');draw.text((col*w+4,y+41),f'Original PTS {t} ms',font=font,fill='white')
            records.append(dict(video=name,side=run['side'],original_pts_ms=t,selection='first/last changed trace and midpoint of first changed nonqualified attempt else middle changed trace',source_sha256=checksum(source),decoded_rgb_sha256=hashlib.sha256(images[t].tobytes()).hexdigest(),cache_sha256=[checksum(p) for p in paths],outside_diagnostics=outside))
    images=[]
    for i,sheet in enumerate(sheets):
        p=DATA/f'in-image-source-original-frames-{i+1}.png';assert not p.exists();sheet.save(p);images.append(dict(path=p.name,sha256=checksum(p)))
    receipt.write_text(json.dumps(dict(kind='original-source-observability-diagnostics-not-new-human-labels',changed_runs_reviewed=len(comparison['changed_runs']),original_frames=len(records),observations=records,images=images,new_form_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n')
    for p in images:print(DATA/p['path'])


if __name__=='__main__':main()
