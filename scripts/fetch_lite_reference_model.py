"""Acquire publisher Lite/float16/1 for research; never replace app model assets."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT/'test-artifacts/exercise-knowledge/lite-model-research'
URL = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task'
CARD = 'https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf'
SIZE = 5777746
MD5 = '04a75ddf7c811ac7a1a4523266dd7d88'
CRC32C = 'Y2/V6A=='
GENERATION = '1682624736756847'


def crc32c(data):
    table = []
    for i in range(256):
        value = i
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
        table.append(value)
    value = 0xFFFFFFFF
    for byte in data:
        value = table[(value ^ byte) & 255] ^ (value >> 8)
    return value ^ 0xFFFFFFFF


def verify_model(data, headers):
    if (len(data) != SIZE or hashlib.md5(data).hexdigest() != MD5 or
            base64.b64encode(crc32c(data).to_bytes(4,'big')).decode() != CRC32C):
        raise ValueError('Publisher size/MD5/CRC32C mismatch')
    if (headers.get('x-goog-generation') != GENERATION or
            headers.get('x-goog-stored-content-length') != str(SIZE) or
            headers.get('ETag',headers.get('etag','')).strip('"') != MD5):
        raise ValueError('Publisher generation/ETag changed')


def main():
    DATA.mkdir(exist_ok=True)
    receipt_path = DATA/'acquisition.json'
    if receipt_path.exists():
        receipt = json.loads(receipt_path.read_bytes())
        model = DATA/'pose_landmarker_lite.task'
        verify_model(model.read_bytes(),receipt['model_response_headers'])
        if hashlib.sha256(model.read_bytes()).hexdigest() != receipt['sha256']:
            raise ValueError('Preserved Lite bytes changed')
        print(json.dumps(receipt,indent=2));return
    if (DATA/'pose_landmarker_lite.task').exists() or (DATA/'model-card.pdf').exists():
        raise FileExistsError('Preserve incomplete acquisition rather than overwrite')
    with urllib.request.urlopen(URL,timeout=45) as response:
        data = response.read(SIZE+1);headers = dict(response.headers)
    verify_model(data,headers)
    with urllib.request.urlopen(CARD,timeout=45) as response:
        card = response.read(4*1024*1024)
    if not card.startswith(b'%PDF-'):
        raise ValueError('Expected original publisher model card PDF')
    (DATA/'pose_landmarker_lite.task').write_bytes(data)
    (DATA/'model-card.pdf').write_bytes(card)
    receipt = dict(schema='senpai-lite-research-model-acquisition/1',captured_utc=datetime.now(timezone.utc).isoformat(),
        url=URL,model_family='MediaPipe Pose Landmarker Lite',version='float16/1',bytes=len(data),
        publisher_generation=GENERATION,publisher_md5=MD5,publisher_crc32c=CRC32C,
        md5_crc32c_verified=True,sha256=hashlib.sha256(data).hexdigest(),model_response_headers=headers,
        license='Apache-2.0',license_source=CARD,license_source_pdf_sha256=hashlib.sha256(card).hexdigest(),
        license_evidence='Publisher model card page2 names Lite/Full/Heavy and states Apache License, Version2.0.',
        official_guide='https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker',
        scope='research only; existing Full/Heavy APK assets and network weights are not replaced',app_assets_modified=False)
    receipt_path.write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(receipt,indent=2))


if __name__ == '__main__':main()
