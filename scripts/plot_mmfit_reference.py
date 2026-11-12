"""Original timestamped RGB and raw MP33 overlays, not new correctness labels."""
import gzip
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont

from extract_mmfit_reference import DATA, ROOT, VIDEO, SOURCE_SHA, sha
from extract_exercise_quality_features import decoded_frames


def render(name, cache, count=6, timestamps=None):
    with gzip.open(cache,'rt') as stream:payload=json.load(stream)
    if payload['source_video_sha256'] != SOURCE_SHA or sha(DATA/VIDEO) != SOURCE_SHA:
        raise ValueError('Original RGB identity mismatch')
    frames=payload['frames']
    indices=sorted({round(i*(len(frames)-1)/(count-1)) for i in range(count)})
    rows={frames[i]['timestamp_ms']:frames[i] for i in indices} if timestamps is None else {
        row['timestamp_ms']:row for row in frames if row['timestamp_ms'] in timestamps}
    if not rows:raise ValueError('No original review timestamps')
    destination=DATA/(name+'.png');receipt=DATA/(name+'.json')
    if destination.exists() or receipt.exists():raise FileExistsError('Preserve original-frame review')
    sheet=Image.new('RGB',(960,310*len(rows)),(20,20,20))
    font=ImageFont.load_default(size=15);records=[]
    decoder=decoded_frames(DATA/VIDEO,payload['sample_period_ms'],max(rows)+100)
    try:
        for timestamp,pixels,_ in decoder:
            if timestamp not in rows:continue
            original=Image.fromarray(pixels);overlay=original.copy();draw=ImageDraw.Draw(overlay)
            points=rows[timestamp]['landmarks']
            if points:
                for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),
                            (23,25),(25,27),(24,26),(26,28),(27,29),(29,31),(28,30),(30,32)]:
                    color=(0,240,120) if min(*points[a][3:5],*points[b][3:5])>=.65 else (140,140,140)
                    draw.line([(points[a][0]*original.width,points[a][1]*original.height),
                               (points[b][0]*original.width,points[b][1]*original.height)],fill=color,width=3)
            index=len(records)
            for col,image in enumerate([original,overlay]):
                image.thumbnail((480,270));sheet.paste(image,(col*480,index*310+40))
                ImageDraw.Draw(sheet).text((col*480+5,index*310+7),
                    f'{"Original RGB" if col==0 else "Raw Full VIDEO"} | PTS {timestamp} ms',font=font,fill='white')
            records.append(dict(original_pts_ms=timestamp,decoded_rgb_sha256=hashlib.sha256(pixels.tobytes()).hexdigest(),
                raw_landmark_count=len(points),confident_landmarks=sum(min(p[3:5])>=.65 for p in points)))
    finally:decoder.close()
    if len(records)!=len(rows):raise ValueError('Missing original timestamps')
    sheet.save(destination)
    receipt.write_text(json.dumps(dict(schema='mmfit-original-frame-review/1',source_sha256=SOURCE_SHA,
        image_sha256=sha(destination),cache_sha256=sha(cache),observations=records,new_labels=0),indent=2)+'\n',newline='\n')
    print(destination)


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proposal-hits',action='store_true')
    args=parser.parse_args()
    if args.proposal_hits:
        report=json.loads((DATA/'proposal-diagnostics.json').read_text())
        times=report['proposal_hit_pts_ms']
        chosen={times[round(i*(len(times)-1)/5)] for i in range(6)} if times else set()
        render('original-proposal-hits',DATA/'coarse.json.gz',timestamps=chosen)
    else:
        render('original-overview',DATA/'coarse.json.gz')
        if (DATA/'fine.json.gz').exists():render('original-selected-bout',DATA/'fine.json.gz')
