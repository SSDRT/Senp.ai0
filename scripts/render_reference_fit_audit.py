"""Inspect original observations behind robust reference recovery; no form ground truth."""
import gzip
import hashlib
import json
from PIL import Image, ImageDraw
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from extract_exercise_quality_features import DATA, FEATURES, decoded_frames
from render_autonomous_pose_audit import EDGES


def main():
    output = DATA/'reference-fit-audit'
    output.mkdir(exist_ok=True)
    cases = [
        ('subject_005_shoulder_good_front', 'complete-reference-full-cache', 'Full reference', [23000, 25000, 28000, 30000]),
        ('subject_005_shoulder_good_front', 'complete-reference-heavy-cache', 'Heavy reference', [23000, 25000, 28000, 30000]),
        ('subject_001_shoulder_good_front', 'autonomous-heavy-cache', 'Heavy source: accepted interval', [10933, 11333, 11733, 12200]),
        ('subject_001_shoulder_good_front', 'autonomous-heavy-cache', 'Heavy source: rejected interval', [1133, 1200, 1333, 1467]),
    ]
    sheet = Image.new('RGB', (1280, 1800), '#151515')
    draw = ImageDraw.Draw(sheet)
    fig, axes = plt.subplots(4, 1, figsize=(13, 11), constrained_layout=True)
    records = []
    for row, (name, directory, label, wanted) in enumerate(cases):
        cache = DATA/directory/(name+'.json.gz')
        with gzip.open(cache, 'rt') as stream:
            video = json.load(stream)
        path = DATA/'videos-v3'/(name+'.mp4')
        assert hashlib.sha256(path.read_bytes()).hexdigest() == video['source_video_sha256']
        samples = {f['timestamp_ms']: f for f in video['frames']}
        times = [min(samples, key=lambda t: abs(t-w)) for w in wanted]
        decoder = decoded_frames(path, 66, None)
        try:
            for timestamp, pixels, _ in decoder:
                if timestamp not in times:
                    continue
                f = samples[timestamp]
                col = times.index(timestamp)
                image = Image.fromarray(pixels)
                canvas = ImageDraw.Draw(image)
                width, height = image.size
                for a, b in EDGES if f['landmarks'] else []:
                    p, q = f['landmarks'][a], f['landmarks'][b]
                    color = (30, 230, 95) if min(p[3], p[4], q[3], q[4]) >= .65 else (130, 130, 130)
                    canvas.line([(p[0]*width, p[1]*height), (q[0]*width, q[1]*height)], fill=color, width=3)
                image.thumbnail((310, 365))
                x, y = col*320, row*450
                sheet.paste(image, (x+(320-image.width)//2, y+65))
                draw.text((x+3, y+3), label, fill='white')
                draw.text((x+3, y+22), f'LEFT | original PTS {timestamp} ms', fill='yellow')
                records.append({'video': video['video'], 'source_sha256': video['source_video_sha256'],
                                'model_sha256': video['model_sha256'], 'timestamp_ms': timestamp,
                                'cache_sha256': hashlib.sha256(cache.read_bytes()).hexdigest()})
                if timestamp >= max(times):
                    break
        finally:
            decoder.close()
        selected = [f for f in video['frames'] if row < 2 or min(times) <= f['timestamp_ms'] <= max(times)]
        for feature in ['angle.left_shoulder', 'angle.left_elbow']:
            index = FEATURES.index(feature)
            axes[row].plot([f['timestamp_ms']/1000 for f in selected],
                           [f['features'][index] if f['features'][index] is not None else float('nan') for f in selected],
                           '.-', markersize=2, label=feature)
        for timestamp in times:
            axes[row].axvline(timestamp/1000, color='gray', alpha=.3)
        axes[row].set_title(label+' | raw model angles; not anatomical truth')
        axes[row].set_xlabel('Original source PTS (seconds)')
        axes[row].set_ylabel('Observed angle (degrees)')
        axes[row].legend()
    assert len(records) == 16
    sheet.save(output/'original-reference-frames.jpg')
    fig.savefig(output/'reference-evidence.png', dpi=125)
    plt.close(fig)
    (output/'provenance.json').write_text(json.dumps({'kind': 'original-observation-diagnostics-not-form-ground-truth',
        'frames': records, 'note': 'Accepted/rejected labels are engine outputs, not human judgments.'}, indent=2)+'\n')
    print('Rendered 16 original timestamped observations: both reference models and accepted/rejected source intervals')


if __name__ == '__main__':
    main()
