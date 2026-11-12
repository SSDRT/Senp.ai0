"""Fetch publisher-pinned UI-PRMD archives; no model training or synthetic labels."""
import argparse
import concurrent.futures
import hashlib
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'test-artifacts/exercise-knowledge/uiprmd'
API='https://zenodo.org/api/records/23104965'
FILES={
    'README.md':('e0e598d5f0a9e1276705ca63e944c349',8014),
    'Segmented Movements.zip':('7626dfdd0ceb3a7cebf9e1e0d0cb173f',206962745),
    'Incorrect Segmented Movements.zip':('edce6554a6a295729a23f8209f50dea4',213516724),
    'Movements.zip':('1b5e3b8ce1506200c630f42f6ca7f4a2',271174975),
    'Incorrect Movements.zip':('d3cdc4f9e56e817a3e5651959441786c',277872825),
}


class TruncatedRangeError(ValueError):
    pass


def checksum(path,algorithm):
    digest=hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def fetch(file):
    name=file['key'];md5,size=FILES[name];target=DATA/name
    if target.exists():
        if target.stat().st_size!=size or checksum(target,'md5')!=md5:
            raise ValueError(f'Existing archive fails publisher checksum: {name}; preserve it for diagnosis')
    else:
        pending=target.with_name(target.name+'.partial')
        # Existing bytes remain untrusted until the whole publisher checksum passes.
        # Request the exact next offset; a resumed partial is never an input to fitting.
        offset=pending.stat().st_size if pending.exists() else 0
        chunk_size=4*1024*1024
        if offset>size:raise ValueError(f'Partial exceeds publisher size: {pending}; inspect before retry')
        with pending.open('ab') as stream:
            while offset<size:
                end=min(size-1,offset+chunk_size-1)
                request=urllib.request.Request(file['links']['self'],headers={'Range':f'bytes={offset}-{end}'})
                for attempt in range(3):
                    try:
                        with urllib.request.urlopen(request,timeout=60) as response:
                            if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {offset}-{end}/{size}':
                                raise ValueError('Publisher did not honor the exact bounded range; refusing an ambiguous partial')
                            block=response.read(end-offset+1)
                        if len(block)!=end-offset+1:raise TruncatedRangeError('Truncated publisher range')
                        break
                    except (OSError,urllib.error.HTTPError,TruncatedRangeError) as error:
                        if attempt==2:raise
                        delay=min(60,int(error.headers.get('Retry-After','10'))) if isinstance(error,urllib.error.HTTPError) else 10
                        print(f'Retrying {name} at {offset}: {type(error).__name__}',flush=True)
                        time.sleep(delay)
                stream.write(block);stream.flush();offset+=len(block)
                if offset%(32*1024*1024)==0:print(f'Downloaded {name}: {offset}/{size} bytes',flush=True)
        if pending.stat().st_size!=size or checksum(pending,'md5')!=md5:
            raise ValueError(f'Download fails publisher checksum: {name}; partial retained')
        pending.rename(target)
    print(f'Verified {name}: {size} bytes',flush=True)
    return {'file':name,'bytes':size,'publisher_md5':md5,'sha256':checksum(target,'sha256'),'url':file['links']['self']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--segmented-only',action='store_true')
    args=parser.parse_args()
    DATA.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(API,timeout=45) as response:raw=response.read()
    record=json.loads(raw)
    (DATA/'record.json').write_bytes(raw)
    selected=[f for f in record['files'] if f['key'] in FILES and
              (not args.segmented_only or f['key'] not in ['Movements.zip','Incorrect Movements.zip'])]
    for file in selected:
        md5,size=FILES[file['key']]
        if file['checksum']!='md5:'+md5 or file['size']!=size:raise ValueError('Publisher manifest changed; review provenance first')
    readme=next(f for f in selected if f['key']=='README.md')
    fetched=[fetch(readme)]
    description=(DATA/'README.md').read_text(encoding='utf-8')
    if 'PDDL' not in description or 'Public Domain Dedication' not in description:raise ValueError('Publisher licence statement changed')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(fetch,file) for file in selected if file['key']!='README.md']
        for future in concurrent.futures.as_completed(futures):fetched.append(future.result())
    manifest={'schema':'uiprmd-publisher-acquisition/1','dataset_doi':'10.5281/zenodo.23104965',
        'paper_doi':'10.3390/data3010002','licence':'Open Data Commons PDDL 1.0',
        'kind':'existing-independent-skeletal-labels-not-mediapipe-camera-truth','files':sorted(fetched,key=lambda r:r['file']),
        'subject_split':{'development':['01','02','03','04'],'validation':['05','06','07'],'test':['08','09','10']},
        'test_subjects_opened':False,'trained_model':False,'readiness_points_earned':0}
    (DATA/'acquisition.json').write_text(json.dumps(manifest,indent=2)+'\n')


if __name__=='__main__':main()
