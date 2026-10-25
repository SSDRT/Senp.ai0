"""Timestamped original squat markers and actual comparator bands, no repair."""
import gzip
import hashlib
import json
import zipfile
from datetime import datetime, timezone
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA, ROOT, checksum
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from prepare_uiprmd_validation import read_validation_member
from uiprmd_vicon import markers, measurement_features, INDEX
from analyze_uiprmd_evidence import read_scope


def main():
    _, records = read_scope('validation')
    reference_index = json.loads((DATA/'development-vicon/manifest.json').read_text())
    entry = next(r for r in reference_index['records'] if (r['subject'], r['movement'], r['correct']) == ('01', 'm01', True))
    reference_path = DATA/'development-vicon'/entry['file']
    if checksum(reference_path, 'sha256') != entry['sha256']:
        raise ValueError('Reference export changed')
    reference = json.loads(reference_path.read_text())
    selected = [(reference, None)]+[(r, r['samples']) for r in records if r['subject'] in {'05', '07'} and r['movement'] == 'm01' and r['correct']]
    links = [('LSHO', 'RSHO'), ('LASI', 'RASI'), ('LSHO', 'LASI'), ('RSHO', 'RASI'),
             ('LSHO', 'LELB'), ('LELB', 'LWRA'), ('RSHO', 'RELB'), ('RELB', 'RWRA'),
             ('LASI', 'LKNE'), ('LKNE', 'LANK'), ('RASI', 'RKNE'), ('RKNE', 'RANK')]
    fig, axes = plt.subplots(3, 5, figsize=(22, 11))
    reads, rendered = [], []
    with zipfile.ZipFile(DATA/'Movements.zip') as archive:
        for i, (record, samples) in enumerate(selected):
            intent = {'member': record['source_member'], 'subject': record['subject'], 'utc': datetime.now(timezone.utc).isoformat()}
            with (DATA/'reference-evidence-marker-access.jsonl').open('a') as access:
                access.write(json.dumps(intent | {'status': 'requested'})+'\n'); access.flush()
                raw = (read_development_member(archive, record['source_member'], {'01', '02', '03', '04'})
                       if record['subject'] == '01' else read_validation_member(archive, record['source_member']))
                digest = hashlib.sha256(raw).hexdigest()
                if digest != record['source_sha256']:
                    raise ValueError('Original marker source changed')
                reads.append(intent | {'sha256': digest})
                access.write(json.dumps(reads[-1] | {'status': 'read'})+'\n')
            array, _ = parse_publisher_array(raw, 'Vicon')
            points, features = markers(array), measurement_features(array)
            # A fixed publisher episode is used only for visual QA, never inference or fitting.
            episode = next(e for e in record['episodes'] if e['episode'] == 2)
            first, last = episode['start_frame'], episode['end_frame']
            peak = first+int(np.nanargmin(features['angle.left_knee'][first:last+1]))
            for col, (frame, plane) in enumerate([(first, (0, 2)), (peak, (0, 2)), (peak, (1, 2))]):
                p = points[frame]; observed = np.isfinite(p).all(axis=1) & (np.linalg.norm(p, axis=1) > 0)
                ax = axes[i, col]
                ax.scatter(p[observed, plane[0]], p[observed, plane[1]], s=8, c='grey')
                for a, b in links:
                    if observed[INDEX[a]] and observed[INDEX[b]]:
                        pair = p[[INDEX[a], INDEX[b]]]
                        ax.plot(pair[:, plane[0]], pair[:, plane[1]], lw=2, c='tab:blue' if a.startswith('L') else 'tab:orange')
                centre = np.median(p[observed, plane[0]])
                ax.set_xlim(centre-900, centre+900); ax.set_ylim(0, 2600); ax.set_aspect('equal')
                ax.set_title(f's{record["subject"]} rep 2 | {frame*10} ms | {"XZ" if plane == (0, 2) else "YZ"}\n{39-int(observed.sum())} missing markers')
                ax.set_xlabel('mm'); ax.set_ylabel('height mm')
                rendered.append({'subject': record['subject'], 'timestamp_ms': frame*10, 'plane': list(plane), 'missing_markers': 39-int(observed.sum())})
            for col, feature in [(3, 'angle.left_knee'), (4, 'angle.left_hip')]:
                ax = axes[i, col]
                ax.plot(np.arange(first, last+1)/100, features[feature][first:last+1], c='black', label='original marker angle')
                if samples is not None:
                    rows = [(s, f) for s in samples if episode['start_ms'] <= s['timestamp_ms'] <= episode['end_ms']
                            for f in s['features'] if f['observed_feature'] == feature]
                    times = [s['timestamp_ms']/1000 for s, _ in rows]
                    ax.fill_between(times, [f['lower_degrees']-f['acceptable_degrees'] for _, f in rows],
                                    [f['upper_degrees']+f['acceptable_degrees'] for _, f in rows],
                                    color='tab:blue', alpha=.2, label='actual acceptable reference band')
                ax.set_title(feature+(' | fixed uploaded target' if samples is None else ' | comparator evidence'))
                ax.set_xlabel('original acquisition seconds'); ax.set_ylabel('marker proxy degrees'); ax.legend(fontsize=7)
    fig.suptitle('UI-PRMD publisher-correct squat examples: original markers, distinct people and unchanged reference target; no new form annotation')
    fig.tight_layout()
    output = DATA/'reference-evidence-original-squat-frames.png'
    fig.savefig(output, dpi=110); plt.close(fig)
    report = {'schema': 'uiprmd-reference-evidence-marker-qa/1', 'original_reads': reads, 'rendered': rendered,
              'plot': str(output.relative_to(ROOT)).replace('\\', '/'), 'plot_sha256': checksum(output, 'sha256'),
              'final_test_subjects_opened': False, 'new_annotations': False}
    (DATA/'reference-evidence-marker-qa.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'original_marker_reads': len(reads), 'rendered_timestamped_views': len(rendered), 'plot': str(output)}))


if __name__ == '__main__':
    main()
