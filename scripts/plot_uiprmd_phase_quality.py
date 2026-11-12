"""Original development marker QA for changed and retained attempts; no new labels."""
import hashlib
import json
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from uiprmd_vicon import INDEX, markers
from train_uiprmd_phase_quality import frozen_protocol


def main():
    protocol = frozen_protocol()
    directory = DATA/'phase-quality-development'
    target = directory/'original-markers.png'
    if target.exists():
        raise FileExistsError('Preserve original marker review')
    result = json.loads((directory/'result.json').read_bytes())
    replay = json.loads((ROOT/protocol['baseline_replay']).read_bytes())
    if result['decision'] != 'REJECTED_AFTER_DEVELOPMENT':
        raise ValueError('QA is scoped to the frozen rejected development result')
    changed = result['changes'][0]
    retained = max((s for s in result['scores'] if s['bad_score_uncalibrated'] < .75 and
        replay['rows'][s['row']]['correct'] and
        replay['rows'][s['row']]['attempts'][s['attempt']]['verdict'] in {'CORRECT', 'ACCEPTABLE'}),
        key=lambda s: s['bad_score_uncalibrated'])
    links = [('LSHO','RSHO'),('LASI','RASI'),('LSHO','LASI'),('RSHO','RASI'),
        ('LSHO','LELB'),('LELB','LWRA'),('RSHO','RELB'),('RELB','RWRA'),
        ('LASI','LKNE'),('LKNE','LANK'),('RASI','RKNE'),('RKNE','RANK')]
    fig, axes = plt.subplots(2, 3, figsize=(12, 8))
    reads, views = [], []
    for i, selected in enumerate((changed, retained)):
        row = replay['rows'][selected['row']]
        attempt = row['attempts'][selected['attempt']]
        name = 'Movements.zip' if row['correct'] else 'Incorrect Movements.zip'
        with zipfile.ZipFile(DATA/name) as archive:
            raw = read_development_member(archive, row['source_member'], {'01','02','03','04'})
        if hashlib.sha256(raw).hexdigest() != row['source_sha256']:
            raise ValueError('Original source changed')
        reads.append(dict(archive=name, member=row['source_member'], sha256=row['source_sha256']))
        array, _ = parse_publisher_array(raw, 'Vicon')
        points = markers(array)
        first = attempt['started_ms']//10
        last = min(len(points)-1, attempt['completed_ms']//10)
        # Fixed temporal midpoint for QA, never a newly annotated or selected fault frame.
        for j, (frame, plane) in enumerate(((first,(0,2)), ((first+last)//2,(1,2)), (last,(0,2)))):
            p = points[frame]
            visible = np.isfinite(p).all(axis=1) & (np.linalg.norm(p,axis=1)>0)
            ax = axes[i,j]
            ax.scatter(p[visible,plane[0]],p[visible,plane[1]],s=8,c='grey')
            for a,b in links:
                if visible[INDEX[a]] and visible[INDEX[b]]:
                    pair = p[[INDEX[a],INDEX[b]]]
                    ax.plot(pair[:,plane[0]],pair[:,plane[1]],lw=2,c='tab:blue' if a.startswith('L') else 'tab:orange')
            centre = np.median(p[visible,plane[0]])
            ax.set_xlim(centre-900,centre+900);ax.set_ylim(0,2600);ax.set_aspect('equal')
            ax.set_xlabel('mm');ax.set_ylabel('height mm')
            label = 'incorrect; vetoed' if i==0 else 'correct; retained'
            ax.set_title(f's{row["subject"]} {row["movement"]} publisher {label}\n{frame*10} ms | {"XZ" if plane==(0,2) else "YZ"} | {39-int(visible.sum())} missing')
            views.append(dict(subject=row['subject'], movement=row['movement'], publisher_correct=row['correct'],
                timestamp_ms=frame*10,plane=list(plane),missing_markers=39-int(visible.sum())))
    fig.suptitle('Phase-quality development QA: original markers; no reconstruction, new labels or camera accuracy')
    fig.tight_layout();fig.savefig(target,dpi=110);plt.close(fig)
    report = dict(schema='uiprmd-phase-quality-original-marker-qa/1',reads=reads,views=views,
        selection='first vetoed attempt and highest-score retained qualified correct attempt; QA only, not fitting',
        image=target.relative_to(ROOT).as_posix(),sha256=checksum(target,'sha256'),
        validation_subjects_opened=False,final_subjects_opened=False,new_human_annotations=False)
    (directory/'original-marker-qa.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('Rendered',len(views),'timestamped original-marker views')


if __name__ == '__main__': main()
