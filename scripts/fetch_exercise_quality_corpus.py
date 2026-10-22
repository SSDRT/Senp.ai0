"""Fetch one verified CC BY 4.0 exercise-video archive, never all duplicate layouts.

Source: Duddela Sai Prashanth et al. (2026), Mendeley Data V3,
doi:10.17632/kgbb3yn47p.3. No videos are committed to Git.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import stat
import time
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]/'test-artifacts/exercise-knowledge'
URL='https://data.mendeley.com/public-files/datasets/kgbb3yn47p/files/a31fa913-6f29-4c31-b1a6-414a718eebf4/file_downloaded'
SIZE=3113452092
SHA='6d927246dbd2ed8a314b41201c94ebdcbd1b4abec0851d1ca39f82afda8a5eed'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download',action='store_true')
    parser.add_argument('--extract',action='store_true')
    args=parser.parse_args()
    ROOT.mkdir(parents=True,exist_ok=True)
    archive=ROOT/'exercise-videos-v3.zip'
    if not archive.exists():
        if not args.download:raise SystemExit('Archive missing; pass --download to fetch 3.11 GB explicitly')
        if shutil.disk_usage(ROOT).free<12*1024**3:raise SystemExit('At least 12 GiB free space required')
        temporary=archive.with_suffix('.partial');digest=hashlib.sha256();count=0;last=0;start=time.monotonic()
        request=urllib.request.Request(URL,headers={'User-Agent':'Mozilla/5.0','Accept':'*/*','Referer':'https://data.mendeley.com/datasets/kgbb3yn47p/3'})
        with urllib.request.urlopen(request,timeout=40) as response,temporary.open('wb') as output:
            while True:
                chunk=response.read(4*1024*1024)
                if not chunk:break
                output.write(chunk);digest.update(chunk);count+=len(chunk)
                if time.monotonic()-last>15:
                    print(f'download {count}/{SIZE} bytes; elapsed {time.monotonic()-start:.0f}s',flush=True);last=time.monotonic()
        if count!=SIZE or digest.hexdigest()!=SHA:raise ValueError('Archive size/SHA-256 mismatch; partial file retained for diagnosis')
        temporary.replace(archive)
    with archive.open('rb') as stream:
        if archive.stat().st_size!=SIZE or hashlib.file_digest(stream,'sha256').hexdigest()!=SHA:raise ValueError('Archive integrity failure')
    print('Verified corpus SHA-256:',SHA,flush=True)
    with zipfile.ZipFile(archive) as zipped:
        entries=zipped.infolist()
        if len(entries)>20000 or sum(i.file_size for i in entries)>12*1024**3:raise ValueError('Unexpected archive expansion')
        print('Archive entries:',len(entries),'expanded bytes:',sum(i.file_size for i in entries),flush=True)
        if args.extract:
            destination=(ROOT/'videos-v3').resolve();destination.mkdir(parents=True,exist_ok=True)
            for item in entries:
                target=(destination/item.filename).resolve()
                if not target.is_relative_to(destination) or stat.S_ISLNK(item.external_attr>>16):raise ValueError('Unsafe archive member')
                if item.is_dir():target.mkdir(parents=True,exist_ok=True);continue
                target.parent.mkdir(parents=True,exist_ok=True)
                if target.is_file() and target.stat().st_size==item.file_size:continue
                with zipped.open(item) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
            print('Extracted corpus to',destination,flush=True)

if __name__=='__main__':main()
