"""Original development markers for the first lost good / withheld bad plane match."""
import json
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import ROOT, DATA, checksum
from analyze_uiprmd_evidence import read_scope
from evaluate_reference_plane_guard import guarded_read
from uiprmd_vicon import INDEX, markers


def main():
    _, records = read_scope('development')
    result = json.loads((DATA/'plane-guard-v2-development.json').read_text())
    selected = [next(t for t in result['traces'] if t['record_correct'] == correct and t['before'] != t['after']) for correct in [True, False]]
    links = [('LSHO', 'RSHO'), ('LASI', 'RASI'), ('LSHO', 'LASI'), ('RSHO', 'RASI'),
             ('LSHO', 'LELB'), ('LELB', 'LWRA'), ('RSHO', 'RELB'), ('RELB', 'RWRA'),
             ('LASI', 'LKNE'), ('LKNE', 'LANK'), ('RASI', 'RKNE'), ('RKNE', 'RANK')]
    fig, axes = plt.subplots(2, 4, figsize=(18, 9)); reads = []; rendered = []
    for i, trace in enumerate(selected):
        record = next(r for r in records if r['subject'] == trace['subject'] and r['movement'] == trace['movement'] and r['correct'] == trace['record_correct'])
        name = 'Movements.zip' if record['correct'] else 'Incorrect Movements.zip'
        with zipfile.ZipFile(DATA/name) as archive:
            array = guarded_read(archive, record, 'development')
        reads.append({'subject': record['subject'], 'source_member': record['source_member'], 'source_sha256': record['source_sha256']})
        rows = trace['guard']['samples']
        worst = max((s for s in rows if s['deviation_degrees'] is not None), key=lambda s: s['deviation_degrees'])
        times = [rows[0]['timestamp_ms'], worst['timestamp_ms'], rows[-1]['timestamp_ms']]
        points = markers(array)
        for col, (timestamp, plane) in enumerate(zip(times, [(0, 2), (1, 2), (0, 2)])):
            p = points[timestamp//10]; present = np.isfinite(p).all(axis=1) & (np.linalg.norm(p, axis=1) > 0)
            ax = axes[i, col]
            ax.scatter(p[present, plane[0]], p[present, plane[1]], s=8, c='grey')
            for a, b in links:
                if present[INDEX[a]] and present[INDEX[b]]:
                    pair = p[[INDEX[a], INDEX[b]]]
                    ax.plot(pair[:, plane[0]], pair[:, plane[1]], lw=2, c='tab:blue' if a.startswith('L') else 'tab:orange')
            centre = np.median(p[present, plane[0]])
            ax.set_xlim(centre-900, centre+900); ax.set_ylim(0, 2600); ax.set_aspect('equal')
            ax.set_title(f's{record["subject"]} publisher {"correct" if record["correct"] else "incorrect"}\n{timestamp}ms | {"XZ" if plane == (0, 2) else "YZ"} | {39-int(present.sum())} missing')
            ax.set_xlabel('mm'); ax.set_ylabel('height mm')
            rendered.append({'subject': record['subject'], 'correct': record['correct'], 'timestamp_ms': timestamp, 'plane': list(plane), 'missing_markers': 39-int(present.sum())})
        ax = axes[i, 3]
        times = [s['timestamp_ms']/1000 for s in rows]
        ax.plot(times, [s['raw_plane_degrees'] for s in rows], c='black', label='original marker-derived arm plane')
        ax.fill_between(times, [s['reference_lower']-s['acceptable'] for s in rows],
                        [s['reference_upper']+s['acceptable'] for s in rows], alpha=.2, label='reference acceptable band')
        ax.set_xlabel('original acquisition seconds'); ax.set_ylabel('plane proxy degrees'); ax.legend(fontsize=7)
        ax.set_title(f'reference s{record["reference_subject"]} | ambiguity {trace["guard"]["ambiguous_fraction"]:.1%}')
    fig.suptitle('Rejected development plane guard: original markers, no repair or new labels; publisher labels retained')
    fig.tight_layout(); path = DATA/'plane-guard-development-original-markers.png'
    fig.savefig(path, dpi=110); plt.close(fig)
    report = {'schema': 'uiprmd-plane-guard-development-qa/1', 'reads': reads, 'rendered': rendered,
              'plot': str(path.relative_to(ROOT)).replace('\\', '/'), 'plot_sha256': checksum(path, 'sha256'),
              'new_validation_trajectories_opened': False, 'final_test_subjects_opened': False, 'new_human_annotations': False}
    (DATA/'plane-guard-development-qa.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'plots': 1, 'original_timestamped_views': len(rendered), 'development_only_reads': len(reads)}))


if __name__ == '__main__':
    main()
