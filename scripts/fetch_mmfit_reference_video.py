"""Fetch one CC BY 4.0 publisher RGB recording; never the separate annotation archive.

Camera-reference development only. No per-rep form labels are implied by this video.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT/'test-artifacts/exercise-knowledge/mmfit'
RECORD = 'https://zenodo.org/api/records/7672767'
VIDEO = 'w19_rgb.mp4'
SIZE = 415360385
MD5 = '5d5f8dd6434deffba8842cfefe0101db'
URL = 'https://zenodo.org/records/7672767/files/w19_rgb.mp4?download=1'


def verify_metadata(record):
    if (record.get('id') != 7672767 or record['metadata']['license']['id'] != 'cc-by-4.0' or
            record['metadata'].get('access_right') != 'open'):
        raise ValueError('Expected open CC BY 4.0 publisher RGB record')
    item = next((r for r in record['files'] if r['key'] == VIDEO), None)
    if item is None or item['size'] != SIZE or item['checksum'] != 'md5:'+MD5:
        raise ValueError('Publisher video size/checksum changed')


def file_hashes(path):
    hashes = {'md5': hashlib.md5(), 'sha256': hashlib.sha256()}
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4*1024*1024), b''):
            for digest in hashes.values(): digest.update(block)
    return {k: v.hexdigest() for k, v in hashes.items()}


def main(download):
    DATA.mkdir(exist_ok=True)
    request = urllib.request.Request(RECORD, headers={'User-Agent': 'Senpai-open-reference-research/1'})
    with urllib.request.urlopen(request, timeout=45) as response:
        metadata_bytes = response.read()
    metadata = json.loads(metadata_bytes)
    verify_metadata(metadata)
    target = DATA/VIDEO
    if not target.exists():
        if not download: raise FileNotFoundError('Use --download for the fixed 415 MB RGB recording')
        if shutil.disk_usage(DATA).free < 2*1024**3: raise ValueError('Require two GiB free workspace')
        temporary = DATA/(VIDEO+'.partial')
        if temporary.exists(): raise FileExistsError('Preserve the previous partial download before retrying')
        started, last, count = time.monotonic(), 0.0, 0
        request = urllib.request.Request(URL, headers={'User-Agent': 'Senpai-open-reference-research/1'})
        with urllib.request.urlopen(request, timeout=45) as response, temporary.open('xb') as stream:
            while True:
                block = response.read(4*1024*1024)
                if not block: break
                stream.write(block);count += len(block)
                if count > SIZE: raise ValueError('Response exceeds frozen publisher size')
                if time.monotonic()-last > 15:
                    print(f'MMFIT {count}/{SIZE} bytes; elapsed {time.monotonic()-started:.0f}s', flush=True)
                    last = time.monotonic()
        if temporary.stat().st_size != SIZE or file_hashes(temporary)['md5'] != MD5:
            raise ValueError('Publisher size/MD5 mismatch; partial retained')
        temporary.replace(target)
    hashes = file_hashes(target)
    if target.stat().st_size != SIZE or hashes['md5'] != MD5: raise ValueError('Existing RGB recording differs from publisher')
    metadata_path = DATA/'zenodo-7672767.json'
    if not metadata_path.exists(): metadata_path.write_bytes(metadata_bytes)
    receipt = {'schema': 'mmfit-reference-video-acquisition/1', 'captured_utc': datetime.now(timezone.utc).isoformat(),
        'source_record': 'https://zenodo.org/records/7672767', 'doi': '10.5281/zenodo.7672767',
        'license': 'CC BY 4.0', 'video': VIDEO, 'url': URL, 'bytes': SIZE, 'publisher_md5': MD5,
        'publisher_md5_verified': True, 'local_sha256': hashes['sha256'],
        'metadata_sha256': hashlib.sha256(metadata_path.read_bytes()).hexdigest(),
        'purpose': 'Raw RGB camera-reference development; no independent binary form labels',
        'separate_pose_sensor_or_annotation_archive_loaded': False, 'new_manual_labels': 0}
    report = DATA/'acquisition.json'
    if not report.exists(): report.write_text(json.dumps(receipt, indent=2)+'\n', newline='\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    main(parser.parse_args().download)
