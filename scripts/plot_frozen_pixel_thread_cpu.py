"""Static-input/source provenance QA plus observed coarse thread-name counters."""
import collections,hashlib,json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import plot_frozen_pixel_camera as qa

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'test-artifacts/exercise-knowledge/frozen-pixel-thread-cpu-20261029'


def main():
    qa.OUT=OUT;qa.main() # Existing separate model/camera image review; original source unchanged.
    report=json.loads((OUT/'analysis.json').read_bytes());groups={True:collections.Counter(),False:collections.Counter()}
    for block in report['thread_results']:
        for row in block['names']:groups[block['camera_active']][row['name']]+=row['cpu_ms']/96
    totals=groups[True]+groups[False];names=[name for name,_ in totals.most_common(8)]
    if not names:raise ValueError('No observed stable thread counters; preserve unknown attribution')
    fig,ax=plt.subplots(figsize=(12,5));x=list(range(len(names)));ax.bar([i-.2 for i in x],[groups[False][n] for n in names],width=.4,label='Quiet: observed named-thread CPU');ax.bar([i+.2 for i in x],[groups[True][n] for n in names],width=.4,label='Camera active: observed named-thread CPU');ax.set_xticks(x,names,rotation=20,ha='right');ax.set_ylabel('Observed stable-identity CPU ms per measured call bracket');ax.set_title('Original procfs names: coarse counters include scan overhead, not native operator identities');ax.legend(fontsize=8);fig.tight_layout();target=OUT/'observed-thread-cpu.png';assert not target.exists();fig.savefig(target,dpi=140);plt.close(fig)
    receipt=OUT/'thread-chart.json';assert not receipt.exists();receipt.write_text(json.dumps(dict(path=target.name,sha256=hashlib.sha256(target.read_bytes()).hexdigest(),kind='coarse-thread-name-counter-observations-not-isolated-native-CPU'),indent=2)+'\n',encoding='utf-8',newline='\n');print('Observed thread-name counters plotted with scan/identity limits')


if __name__=='__main__':main()
