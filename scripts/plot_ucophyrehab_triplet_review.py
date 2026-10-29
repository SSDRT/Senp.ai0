"""Layout-only review of the exact frozen first/middle/last original triplets."""
import hashlib
import json

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import MaxNLocator
import numpy as np

from audit_ucophyrehab_triangle import DATA, PROTOCOL, JOINTS, coordinates


def main():
    output = DATA/'original-triplets-layout-reviewed.png'
    if output.exists(): raise FileExistsError('Preserve layout review')
    source = DATA/'selected-joint-schema.json'
    protocol = json.loads(PROTOCOL.read_bytes())
    if hashlib.sha256(source.read_bytes()).hexdigest() != protocol['selected_schema_sha256']:
        raise ValueError('Original selected input changed')
    original = json.loads((DATA/'original-frame-receipts.json').read_bytes())
    frames = json.loads(source.read_bytes())['frames']
    indices = [0,len(frames)//2,len(frames)-1]
    actual = [dict(original_frame_ordinal=i,original_frame_id=frames[i]['id'],original_points=frames[i]['joints']) for i in indices]
    if actual != original['frames']: raise ValueError('Snapshot identity or original coordinates changed')
    fig = plt.figure(figsize=(19,6),layout='constrained')
    colors = ['tab:blue','tab:orange','tab:green']
    for column,index in enumerate(indices):
        p = np.asarray(coordinates(frames[index]))
        ax=fig.add_subplot(1,3,column+1,projection='3d')
        ax.plot(*p.T,color='black',linewidth=1)
        for point,color in zip(p,colors):ax.scatter(*point,color=color,s=45)
        span = np.maximum(np.ptp(p,axis=0),1e-9)
        for axis,low,high,size in zip((ax.set_xlim,ax.set_ylim,ax.set_zlim),p.min(axis=0),p.max(axis=0),span):axis(low-.15*size,high+.15*size)
        for axis in (ax.xaxis,ax.yaxis,ax.zaxis):axis.set_major_locator(MaxNLocator(3))
        ax.set_box_aspect(span);ax.view_init(15,45);ax.tick_params(labelsize=9,pad=0)
        ax.set_xlabel('Recorded X',labelpad=1);ax.set_ylabel('Recorded Y',labelpad=1);ax.set_zlabel('Recorded Z',labelpad=1)
        ax.set_title(f"Original frame ID {frames[index]['id']} | time basis unverified",fontsize=12)
        ax.legend([Line2D([],[],color=c,marker='o',linestyle='') for c in colors],JOINTS,loc='upper left',fontsize=9)
    fig.suptitle('Original OptiTrack triplets | Subject1, exercise1 | Coordinate units not audited | No inferred points',fontsize=14)
    fig.savefig(output,dpi=130);plt.close(fig)
    receipt = dict(schema='ucophyrehab-triplet-layout-review/1',frames=actual,
        identical_to_original_frame_receipts=True,original_plot_preserved=True,
        changes='Padded original coordinate bounds, reduced tick density and moved point names into legends; no data, view direction or snapshot selection changes',
        image_sha256=hashlib.sha256(output.read_bytes()).hexdigest())
    (DATA/'layout-review-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('Three frozen frame/point records identical; original image retained')


if __name__ == '__main__': main()
