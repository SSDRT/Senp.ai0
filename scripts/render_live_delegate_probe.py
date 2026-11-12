"""Pinned original static fixture with unchanged recorded native overlays, not rep truth."""
import argparse,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw',type=Path,required=True);parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.json').exists():raise FileExistsError('Preserve fixture review')
    report=json.loads(args.raw.read_bytes())
    sha=hashlib.sha256(args.fixture.read_bytes()).hexdigest()
    if sha!=report['fixture_sha256']:raise ValueError('Original fixture changed')
    original=Image.open(args.fixture).convert('RGB');font=ImageFont.load_default(size=14)
    sheet=Image.new('RGB',(1200,2*440),(22,22,22));records=[]
    for row,model in enumerate(['FULL','HEAVY']):
        images=[original.copy()];titles=['Pinned original fixture']
        for delegate in ['CPU','GPU']:
            entry=next(r for r in report['rows'] if r['model']==model and r['scene']=='pose' and r['requested_delegate']==delegate)
            frame=entry['frames'][-1];points=frame['raw_landmarks'];overlay=original.copy();draw=ImageDraw.Draw(overlay)
            for a,b in [(11,12),(11,13),(13,15),(12,14),(14,16),(11,23),(12,24),(23,24),(23,25),(25,27),(24,26),(26,28)]:
                color=(0,240,120) if min(points[a]['visibility'],points[a]['presence'],points[b]['visibility'],points[b]['presence'])>=.65 else (140,140,140)
                draw.line([(points[a]['image'][0]*overlay.width,points[a]['image'][1]*overlay.height),
                           (points[b]['image'][0]*overlay.width,points[b]['image'][1]*overlay.height)],fill=color,width=3)
            images.append(overlay);titles.append(f'{model} request {delegate}, actual {frame["actual_delegate"]}')
            records.append(dict(model=model,requested_delegate=delegate,actual_delegate=frame['actual_delegate'],
                static_diagnostic_timestamp_ms=frame['diagnostic_timestamp_ms'],raw_landmarks=33))
        for col,(image,title) in enumerate(zip(images,titles)):
            image.thumbnail((400,380));sheet.paste(image,(400*col,440*row+50))
            ImageDraw.Draw(sheet).text((400*col+4,440*row+5),title,font=font,fill='white')
            ImageDraw.Draw(sheet).text((400*col+4,440*row+25),'Static repeated image; no exercise rep labels',font=font,fill='white')
    sheet.save(args.output)
    args.output.with_suffix('.json').write_text(json.dumps(dict(schema='senpai-static-delegate-frame-review/1',
        raw_sha256=hashlib.sha256(args.raw.read_bytes()).hexdigest(),fixture_sha256=sha,
        sheet_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest(),records=records,new_human_labels=0),indent=2)+'\n',encoding='utf-8',newline='\n')
    print(args.output)


if __name__=='__main__':main()
