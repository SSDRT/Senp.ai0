"""Retain paired rep changes and inspect original markers for the changed side."""
import hashlib
import json
import subprocess
import zipfile
from collections import Counter
from datetime import datetime, timezone
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import DATA, ROOT, checksum
from evaluate_uiprmd_side_binding import PROTOCOL, compare
from inspect_uiprmd_development import parse_publisher_array
from prepare_uiprmd_validation import read_validation_member
from uiprmd_vicon import INDEX, markers, measurement_features

FREEZE = 'cb6396c'


def main():
    protocol = json.loads(PROTOCOL.read_text())
    committed = json.loads(subprocess.check_output(['git', 'show', FREEZE+':models/uiprmd-side-binding-protocol.json'], cwd=ROOT))
    if protocol != committed or protocol['allow_final_test_access']:
        raise ValueError('Research protocol differs from its pre-validation commit')
    subprocess.run(['git', 'merge-base', '--is-ancestor', FREEZE, 'HEAD'], cwd=ROOT, check=True)
    subprocess.run(['git', 'diff', '--quiet', FREEZE, '--', 'core-motion/src/main',
                    'core-contracts/src/main', 'core-pipeline/src/main'], cwd=ROOT, check=True)
    for path, digest in protocol['source_sha256'].items():
        if checksum(ROOT/path, 'sha256') != digest:
            raise ValueError('Frozen candidate source changed: '+path)
    changes = {}
    for scope in ['development', 'validation']:
        fixed, causal = [json.loads((DATA/f'side-binding-{scope}-{mode}-replay.json').read_text()) for mode in ['fixed', 'causal']]
        result = compare(fixed, causal, scope, checksum(PROTOCOL, 'sha256'))
        def index(mode):
            return {(r['subject'], r['movement'], r['correct'], r['episode']): r['verdict'] for r in result['results'][mode]['rep_results']}
        old, new = index('fixed'), index('causal_variant')
        qualified = {'CORRECT', 'ACCEPTABLE'}
        changes[scope] = {'previously_counted_good_reps_lost': sum(k[2] and old[k] in qualified and new[k] not in qualified for k in old),
                          'good_counts_gained': sum(k[2] and old[k] not in qualified and new[k] in qualified for k in old),
                          'incorrect_counts_added': sum(not k[2] and old[k] not in qualified and new[k] in qualified for k in old),
                          'changed_reps': [{'subject': k[0], 'movement': k[1], 'correct': k[2], 'episode': k[3],
                                            'before': old[k], 'after': new[k]} for k in old if old[k] != new[k]],
                          'replay_sha256': {mode: checksum(DATA/f'side-binding-{scope}-{mode}-replay.json', 'sha256') for mode in ['fixed', 'causal']}}
    manifest = json.loads((DATA/'validation-vicon/manifest.json').read_text())
    if checksum(DATA/'validation-vicon/manifest.json', 'sha256') != protocol['validation_manifest_sha256']:
        raise ValueError('Validation export changed')
    replay = json.loads((DATA/'side-binding-validation-causal-replay.json').read_text())
    output = DATA/'side-binding-original-marker-qa'
    output.mkdir(exist_ok=True)
    links = [('LSHO', 'RSHO'), ('LASI', 'RASI'), ('LSHO', 'LASI'), ('RSHO', 'RASI'),
             ('LSHO', 'LELB'), ('LELB', 'LWRA'), ('RSHO', 'RELB'), ('RELB', 'RWRA'),
             ('LASI', 'LKNE'), ('LKNE', 'LANK'), ('RASI', 'RKNE'), ('RKNE', 'RANK')]
    plots, reads, reasons = [], [], []
    for row in replay['rows']:
        if row['movement'] != 'm07':
            continue
        reasons.append({'subject': row['subject'], 'correct': row['correct'],
                        'verdicts': dict(Counter(a['verdict'] for a in row['attempts'])),
                        'reasons_overlap': dict(Counter(reason for a in row['attempts'] for reason in a['reasons']))})
        if row['subject'] != '07':
            continue
        binding = next(e for e in row['binding_events'] if e['state'] == 'LOCKED')
        record = next(r for r in manifest['records'] if (r['subject'], r['movement'], r['correct']) == ('07', 'm07', row['correct']))
        export = DATA/'validation-vicon'/record['file']
        if checksum(export, 'sha256') != record['sha256']:
            raise ValueError('Export changed')
        archive_name = 'Movements.zip' if row['correct'] else 'Incorrect Movements.zip'
        intent = {'archive': archive_name, 'member': row['source_member'], 'candidate_freeze_commit': FREEZE,
                  'candidate_protocol_sha256': checksum(PROTOCOL, 'sha256'), 'utc': datetime.now(timezone.utc).isoformat()}
        with (DATA/'side-binding-marker-access.jsonl').open('a') as access:
            access.write(json.dumps(intent | {'status': 'requested'})+'\n'); access.flush()
            with zipfile.ZipFile(DATA/archive_name) as archive:
                raw = read_validation_member(archive, row['source_member'])  # Rejects subjects 08–10 before archive.read.
            digest = hashlib.sha256(raw).hexdigest()
            if digest != row['source_sha256']:
                raise ValueError('Original marker source changed')
            reads.append(intent | {'sha256': digest})
            access.write(json.dumps(reads[-1] | {'status': 'read'})+'\n')
        array, _ = parse_publisher_array(raw, 'Vicon')
        points, features = markers(array), measurement_features(array)
        attempt = row['attempts'][0]
        start, end = attempt['started_ms']//10, attempt['completed_ms']//10
        driver = features['angle.'+binding['side'].lower()+'_shoulder']
        peak = start+int(np.nanargmax(driver[start:end+1]))
        times = [binding['locked_ms']//10, peak, end]
        fig, axes = plt.subplots(2, 4, figsize=(17, 8))
        rendered = []
        for i, plane in enumerate([(0, 2), (1, 2)]):
            for col, frame in enumerate(times):
                p = points[frame]; observed = np.isfinite(p).all(axis=1) & (np.linalg.norm(p, axis=1) > 0)
                ax = axes[i, col]
                ax.scatter(p[observed, plane[0]], p[observed, plane[1]], s=8, c='grey')
                for a, b in links:
                    if observed[INDEX[a]] and observed[INDEX[b]]:
                        pair = p[[INDEX[a], INDEX[b]]]
                        ax.plot(pair[:, plane[0]], pair[:, plane[1]], lw=2, c='tab:blue' if a.startswith('L') else 'tab:orange')
                centre = np.median(p[observed, plane[0]])
                ax.set_xlim(centre-900, centre+900); ax.set_ylim(0, 2100); ax.set_aspect('equal')
                ax.set_title(f'{frame*10} ms | {"XZ" if i == 0 else "YZ"} | {39-int(observed.sum())} missing')
                ax.set_xlabel('mm'); ax.set_ylabel('height mm')
                rendered.append({'timestamp_ms': frame*10, 'plane': list(plane), 'missing_markers': 39-int(observed.sum())})
            ax = axes[i, 3]
            joint = 'shoulder' if i == 0 else 'elbow'
            for side, color in [('left', 'tab:blue'), ('right', 'tab:orange')]:
                ax.plot(np.arange(len(array))/100, features[f'angle.{side}_{joint}'], c=color, label=f'{side}_{joint}')
            ax.axvline(binding['locked_ms']/1000, c='black', ls='--', label='causal side lock')
            ax.axvspan(attempt['started_ms']/1000, attempt['completed_ms']/1000, alpha=.15, color='grey')
            ax.set_xlabel('original acquisition seconds'); ax.set_ylabel('marker proxy degrees'); ax.legend(fontsize=8)
        fig.suptitle(f'Original Vicon39 s07 m07 publisher {"correct" if row["correct"] else "incorrect"}; locked {binding["side"]}; no marker repair')
        fig.tight_layout()
        target = output/f's07-m07-{"correct" if row["correct"] else "incorrect"}.png'
        fig.savefig(target, dpi=110); plt.close(fig)
        plots.append({'path': str(target.relative_to(ROOT)).replace('\\', '/'), 'sha256': checksum(target, 'sha256'), 'rendered': rendered})
    report = {'schema': 'uiprmd-side-binding-error-audit/1', 'candidate_freeze_commit': FREEZE,
              'candidate_protocol_sha256': checksum(PROTOCOL, 'sha256'), 'paired_changes': changes,
              'shoulder_attempt_reasons': reasons, 'original_marker_reads': reads, 'plots': plots,
              'final_test_subjects_opened': False, 'production_changed': False, 'readiness_points_earned': 0}
    (DATA/'side-binding-error-audit.json').write_text(json.dumps(report, indent=2, allow_nan=False)+'\n')
    print(json.dumps({'paired_changes': {k: {n: v for n, v in r.items() if n != 'changed_reps'} for k, r in changes.items()},
                      'original_marker_plots': len(plots), 'final_test_subjects_opened': False}, indent=2))


if __name__ == '__main__':
    main()
