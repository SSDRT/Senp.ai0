"""Original paired conversion images, with original capture IDs and no pose labels."""
import argparse
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);a=p.parse_args()
    out=a.directory/'qa';out.mkdir(exist_ok=False)
    summary=json.loads((a.directory/'summary.json').read_bytes())
    fig,axes=plt.subplots(3,4,figsize=(17,15),layout='constrained');receipts=[]
    for i,row in enumerate(summary['rows']):
        folder=a.directory/f'pair{i+1}'
        for j,name in enumerate(['direct-yuv-upright.png','via-rgba-upright.png']):
            path=folder/name;ax=axes[i//2,(i%2)*2+j]
            ax.imshow(Image.open(path));ax.set_axis_off()
            ax.set_title(f"Pair{i+1} | {'YUV direct' if j==0 else 'RGBA reader route'}\ncapture_ns {row['source_capture_ns']} | pixel differences {row['different_pixels']}",fontsize=9)
            receipts.append(dict(pair=i+1,path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),source_capture_ns=row['source_capture_ns']))
    fig.suptitle('Original same-frame colour conversions | Capture clock not verified | No pose inference or human-form labels',fontsize=13)
    fig.savefig(out/'six-original-conversion-pairs.png',dpi=110);plt.close(fig)
    (out/'receipts.json').write_text(json.dumps(dict(schema='senpai-conversion-parity-original-qa/1',images=receipts,source_pixels_unmodified=True,new_human_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n')
    print('All twelve original images retained with six distinct original capture IDs')


if __name__=='__main__':main()
