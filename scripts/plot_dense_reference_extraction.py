"""Original timestamped RGB plus unchanged raw 66/33 ms inference overlays.

Fixed frames are diagnostics, never new correctness labels or a source-outcome selector.
"""
import gzip
import hashlib
import json
from PIL import Image, ImageDraw, ImageFont

from extract_exercise_quality_features import DATA, ROOT, decoded_frames


def load(path):
    with gzip.open(path, 'rt') as stream:
        return json.load(stream)


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    names = ['subject_001_squat_good_front', 'subject_005_squat_good_side', 'subject_001_push_up_good_side']
    width, height = 480, 320
    sheet = Image.new('RGB', (width*3, height*6), (20, 20, 20))
    font = ImageFont.load_default(size=14)
    receipts, index = [], 0
    for name in names:
        old_path = DATA/'complete-reference-full-cache'/(name+'.json.gz')
        dense_path = DATA/'dense-reference-full-cache'/(name+'.json.gz')
        old, dense = load(old_path), load(dense_path)
        if old['source_video_sha256'] != dense['source_video_sha256'] or old['model_sha256'] != dense['model_sha256']:
            raise ValueError('Overlay identities differ')
        source = DATA/'videos-v3'/(name+'.mp4')
        if checksum(source) != old['source_video_sha256']:
            raise ValueError('Original RGB source changed')
        a = {r['timestamp_ms']: r for r in old['frames']}
        b = {r['timestamp_ms']: r for r in dense['frames']}
        shared = sorted(set(a)&set(b))
        times = [min(shared, key=lambda t: abs(t-requested)) for requested in [6000, 10000]]
        images = {}
        frames = decoded_frames(source, 33, max(times)+100)
        try:
            for t, pixels, _ in frames:
                if t in times:
                    images[t] = pixels.copy()
        finally:
            frames.close()
        for t in times:
            raw = images[t]
            original = Image.fromarray(raw)
            cells = [original.copy(), original.copy(), original.copy()]
            for image, frame in zip(cells[1:], [a[t], b[t]]):
                points = frame['landmarks']
                if not points:
                    continue
                if len(points) != 33:
                    raise ValueError('No landmark padding is permitted')
                draw = ImageDraw.Draw(image)
                w, h = image.size
                confidence = [min(p[3], p[4]) for p in points]
                for u, v in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),
                              (23,25),(25,27),(24,26),(26,28),(27,29),(29,31),(28,30),(30,32)]:
                    color = (0, 230, 100) if min(confidence[u], confidence[v]) >= .65 else (140, 140, 140)
                    draw.line([(points[u][0]*w, points[u][1]*h),(points[v][0]*w, points[v][1]*h)], fill=color, width=3)
                for i, point in enumerate(points):
                    x, y = point[0]*w, point[1]*h
                    color = (255, 225, 0) if confidence[i] >= .65 else (140, 140, 140)
                    draw.ellipse((x-2, y-2, x+2, y+2), fill=color)
            for col, (image, title) in enumerate(zip(cells, ['Original RGB', 'Full / 66 ms', 'Full / 33 ms'])):
                image.thumbnail((width, height-48))
                sheet.paste(image, (col*width+(width-image.width)//2, index*height+48))
                draw = ImageDraw.Draw(sheet)
                draw.text((col*width+6,index*height+5),f'{title} | original PTS {t} ms',font=font,fill='white')
                draw.text((col*width+6,index*height+25),name,font=font,fill='white')
            receipts.append({'video': source.name, 'original_pts_ms': t,
                'original_video_sha256': checksum(source), 'decoded_rgb_sha256': hashlib.sha256(raw.tobytes()).hexdigest(),
                'old_cache_sha256': checksum(old_path), 'dense_cache_sha256': checksum(dense_path),
                'old_raw_mp33': a[t]['landmarks'], 'dense_raw_mp33': b[t]['landmarks']})
            index += 1
    image_path = DATA/'dense-reference-original-frames.png'
    qa_path = DATA/'dense-reference-original-frames.json'
    if image_path.exists() or qa_path.exists():
        raise FileExistsError('Preserve completed timestamped QA')
    sheet.save(image_path)
    qa_path.write_text(json.dumps({'kind':'original-rgb-and-raw-pose-diagnostics-not-human-rep-labels',
        'image_path':image_path.relative_to(ROOT).as_posix(),'image_sha256':checksum(image_path),
        'fixed_requested_pts_ms':[6000,10000],'observations':receipts,'new_labels':0},indent=2)+'\n')
    print(image_path)


if __name__ == '__main__':
    main()
