"""Read the public ZIP with HTTP ranges and save CRC/SHA-verified video members.

Avoids duplicate archive/extracted copies. Whole-archive SHA is declared by the
publisher but cannot be claimed verified when downloading individual members.
"""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import struct
import time
import urllib.request
import zipfile
import zlib
from fetch_exercise_quality_corpus import ROOT,URL,SIZE,SHA

HEADERS={'User-Agent':'Mozilla/5.0','Accept':'*/*','Referer':'https://data.mendeley.com/datasets/kgbb3yn47p/3'}
def fetch_range(start,end):
    last=None
    for attempt in range(3):
        try:
            request=urllib.request.Request(URL,headers=HEADERS|{'Range':f'bytes={start}-{end}'})
            with urllib.request.urlopen(request,timeout=45) as response:
                if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{end}/{SIZE}':raise ValueError('Unexpected range response')
                data=response.read()
            if len(data)!=end-start+1:raise ValueError('Truncated range response')
            return data
        except Exception as error:last=error
    raise last

class RemoteZip(io.RawIOBase):
    def __init__(self):self.position=0
    def seek(self,offset,whence=0):
        self.position=offset if whence==0 else self.position+offset if whence==1 else SIZE+offset
        return self.position
    def tell(self):return self.position
    def read(self,size=-1):
        count=SIZE-self.position if size<0 else min(size,SIZE-self.position)
        if count<=0:return b''
        data=fetch_range(self.position,self.position+count-1);self.position+=count;return data

def save_member(item):
    destination=ROOT/'videos-v3'/Path(item.filename).name
    if item.file_size>128*1024**2:raise ValueError('Unexpected individual video size')
    data=destination.read_bytes() if destination.is_file() else None
    if data is None or len(data)!=item.file_size or zlib.crc32(data)!=item.CRC:
        start=item.header_offset
        block=fetch_range(start,min(SIZE-1,start+item.compress_size+4095))
        if block[:4]!=b'PK\x03\x04':raise ValueError('Invalid ZIP member header')
        name_size,extra_size=struct.unpack_from('<HH',block,26)
        content_start=30+name_size+extra_size
        payload=block[content_start:content_start+item.compress_size]
        if len(payload)!=item.compress_size:raise ValueError('ZIP local header exceeds bounded padding')
        if item.compress_type==zipfile.ZIP_STORED:data=payload
        elif item.compress_type==zipfile.ZIP_DEFLATED:
            decoder=zlib.decompressobj(-zlib.MAX_WBITS)
            data=decoder.decompress(payload,item.file_size+1)
            if decoder.unconsumed_tail or not decoder.eof:raise ValueError('Unexpected ZIP expansion')
        else:raise ValueError('Unsupported ZIP compression')
        if len(data)!=item.file_size or zlib.crc32(data)!=item.CRC:raise ValueError('Video ZIP CRC mismatch')
        temporary=destination.with_suffix('.partial');temporary.write_bytes(data);temporary.replace(destination)
    match=re.fullmatch(r'subject_(\d+)_(abs|back|bicep_curl|bicep|push_up|shoulder|squat|tricep)_(good|bad)_(front|side|diagonal)\.mp4',destination.name)
    if not match:raise ValueError('Unexpected labelled filename')
    return {'file':destination.name,'subject':match[1],'exercise':'bicep_curl' if match[2]=='bicep' else match[2],'original_exercise_token':match[2],'quality':match[3],'view':match[4],
            'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'zip_crc32':item.CRC,'member_crc_verified':True}

def main():
    ROOT.mkdir(parents=True,exist_ok=True);(ROOT/'videos-v3').mkdir(exist_ok=True)
    if shutil.disk_usage(ROOT).free<8*1024**3:raise SystemExit('At least 8 GiB free space required')
    with zipfile.ZipFile(RemoteZip()) as archive:entries=[i for i in archive.infolist() if i.filename.lower().endswith('.mp4')]
    if len(entries)!=1042 or len({Path(i.filename).name for i in entries})!=len(entries):raise ValueError('Unexpected video index/duplicate basenames')
    manifest={'dataset':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0','publisher_archive_sha256':SHA,
              'full_archive_sha256_verified':False,'integrity':'HTTPS range reads, ZIP CRC and locally recorded per-video SHA-256',
              'expected_videos':len(entries),'videos':[],'failures':[]}
    start=time.monotonic();last=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for future in concurrent.futures.as_completed([pool.submit(save_member,item) for item in entries]):
            try:manifest['videos'].append(future.result())
            except Exception as error:
                manifest['failures'].append(repr(error));print('MEMBER ERROR',repr(error),flush=True)
            if time.monotonic()-last>15 or len(manifest['videos'])==len(entries):
                print(f"verified videos {len(manifest['videos'])}/{len(entries)}, {sum(r['bytes'] for r in manifest['videos'])} bytes, elapsed {time.monotonic()-start:.0f}s",flush=True)
                (ROOT/'corpus-video-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');last=time.monotonic()
    manifest['videos'].sort(key=lambda r:r['file'])
    (ROOT/'corpus-video-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    if manifest['failures'] or len(manifest['videos'])!=1042:raise SystemExit('Corpus download incomplete; see recorded failures')

if __name__=='__main__':main()
