"""Original RGB/raw MP33 at predeclared reference-window boundaries; no rep labels."""
import gzip
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont
from extract_exercise_quality_features import DATA, decoded_frames


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    names = ['subject_005_bicep_curl_good_side', 'subject_005_shoulder_good_front']
    planned_times = [0, 8000, 16000, 24000]
    image_path = DATA / 'fixed-reference-window-original-camera.png'
    receipt_path = DATA / 'fixed-reference-window-original-camera.json'
    if image_path.exists() or receipt_path.exists():
        raise FileExistsError('Preserve fixed-window frame review')
    sheet = Image.new('RGB', (1200, len(names) * len(planned_times) * 265), (20, 20, 20))
    font = ImageFont.load_default(size=13)
    records = []
    for name in names:
        source = DATA / 'videos-v3' / (name + '.mp4')
        caches = [DATA / f'complete-reference-{model}-cache' / (name + '.json.gz') for model in ['full', 'heavy']]
        payloads = []
        for cache in caches:
            with gzip.open(cache, 'rt') as stream:
                payloads.append(json.load(stream))
        source_sha = sha(source)
        if {p['source_video_sha256'] for p in payloads} != {source_sha}:
            raise ValueError('Original source/cache identity mismatch')
        rows = [{r['timestamp_ms']: r for r in p['frames']} for p in payloads]
        if set(rows[0]) != set(rows[1]):
            raise ValueError('Model input timebases differ')
        selected = {min(rows[0], key=lambda t: (abs(t-planned), t)): planned for planned in planned_times}
        if len(selected) != 4:
            raise ValueError('Original boundary observations unavailable')
        decoder = decoded_frames(source, 66, max(selected) + 100)
        count = 0
        try:
            for t, pixels, _ in decoder:
                if t not in selected:
                    continue
                panels = [Image.fromarray(pixels) for _ in range(3)]
                for col, cached in enumerate(rows, 1):
                    points = cached[t]['landmarks']
                    draw = ImageDraw.Draw(panels[col])
                    for a, b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                        if not points:
                            continue
                        color = (0,235,100) if min(*points[a][3:5],*points[b][3:5]) >= .65 else (140,140,140)
                        draw.line([(points[a][0]*panels[col].width,points[a][1]*panels[col].height),
                                   (points[b][0]*panels[col].width,points[b][1]*panels[col].height)],fill=color,width=3)
                for col, (panel, title) in enumerate(zip(panels, ['Original RGB', 'Pinned raw Full', 'Pinned raw Heavy'])):
                    panel.thumbnail((400,220));sheet.paste(panel,(400*col,265*len(records)+45))
                    draw = ImageDraw.Draw(sheet)
                    draw.text((400*col+4,265*len(records)+4),f'{title} | original PTS {t}ms',font=font,fill='white')
                    draw.text((400*col+4,265*len(records)+23),name,font=font,fill='white')
                records.append(dict(video=source.name,source_sha256=source_sha,planned_boundary_ms=selected[t],
                                    original_pts_ms=t,rgb_sha256=hashlib.sha256(pixels.tobytes()).hexdigest(),
                                    cache_sha256=[sha(p) for p in caches]))
                count += 1
        finally:
            decoder.close()
        if count != 4:
            raise ValueError('Original RGB boundary observations missing')
    sheet.save(image_path)
    receipt_path.write_text(json.dumps(dict(schema='fixed-reference-window-original-frame-qa/1',
        kind='original-camera-boundary-diagnostics-not-human-rep-labels',observations=records,
        image_sha256=sha(image_path),new_rep_labels=0),indent=2)+'\n',newline='\n')
    print(image_path)


if __name__ == '__main__':
    main()
