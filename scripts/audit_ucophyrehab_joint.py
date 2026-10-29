"""Frozen single-subject joint-schema intake, not a form or RGB benchmark.

Only subject1/exercise1 frame objects are constructed. Other records' JSON tokens
are consumed for syntax/metadata, but their trajectory values are not retained,
inspected, evaluated or emitted. No ordinal-rating file is acquired.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT/'test-artifacts/exercise-knowledge/ucophyrehab-joint-audit-20261029'
PROTOCOL = ROOT/'models/ucophyrehab-joint-intake-protocol.json'


def subject_or_exercise(value):
    if type(value) is int and value > 0:
        return value
    if isinstance(value, str) and value.isascii() and value.isdecimal() and int(value) > 0:
        return int(value)
    raise ValueError('Original positive numeric identity required; do not guess folder mapping')


def select_joint_frames(stream):
    import ijson
    from ijson.common import ObjectBuilder
    if importlib.metadata.version('ijson') != '3.6.0':
        raise ValueError('Frozen streaming-parser version required')
    events = iter(ijson.parse(stream))
    headers = None
    records, frames, schema, root_keys = [], [], {}, []
    chosen = []
    data_seen = False
    for prefix, event, value in events:
        if prefix == '' and event == 'map_key': root_keys.append(value)
        if (prefix, event) == ('data', 'start_array'): data_seen = True
        if (prefix, event) == ('data.item', 'start_map'): headers = {}
        if prefix in {'data.item.folder', 'data.item.exercise'}:
            key = prefix.rsplit('.', 1)[1]
            if key in headers: raise ValueError('Duplicate recording identity')
            headers[key] = subject_or_exercise(value)
        if (prefix, event) == ('data.item.frames', 'start_array'):
            if headers is None or set(headers) != {'folder', 'exercise'}:
                raise ValueError('Identity must precede frame access; no fallback or reordering')
            if headers == {'folder': 1, 'exercise': 1}:
                chosen.append(len(records))
                if len(chosen) != 1: raise ValueError('Ambiguous duplicate selected recording')
        selected = headers == {'folder': 1, 'exercise': 1}
        if selected and prefix == 'data.item.frames.item' and event == 'start_map':
            builder = ObjectBuilder()
            builder.event(event, value)
            depth = 1
            for p, e, v in events:
                schema.setdefault(p.removeprefix('data.item.frames.item'), set()).add(e)
                builder.event(e, v)
                if e in {'start_map', 'start_array'}: depth += 1
                if e in {'end_map', 'end_array'}: depth -= 1
                if depth == 0: break
            if depth != 0: raise ValueError('Incomplete selected frame')
            frames.append(builder.value)
            if len(frames) > 50000: raise ValueError('Unexpected selected recording size')
        if (prefix, event) == ('data.item', 'end_map'):
            if headers is None or set(headers) != {'folder', 'exercise'}:
                raise ValueError('Missing recording identity')
            records.append(dict(headers))
            headers = None
    if not data_seen or len(chosen) != 1 or not frames:
        raise ValueError('Expected data array and exactly one subject1/exercise1 recording')
    return dict(root_keys=root_keys, metadata_roster=records,
                selected_record_index=chosen[0], selected_subject=1, selected_exercise=1,
                frame_schema={k: sorted(v) for k, v in sorted(schema.items())}, frames=frames,
                other_record_trajectory_objects_constructed=False,
                other_record_JSON_tokens_consumed=True)


def protocol():
    spec = json.loads(PROTOCOL.read_bytes())
    if (spec['schema'] != 'ucophyrehab-joint-intake-protocol/1' or
            spec['selected_subject'] != 1 or spec['selected_exercise'] != 1 or
            spec['file']['key'] != 'dataset_3d_with_angles.json'):
        raise ValueError('Unexpected prospective protocol')
    from audit_ucophyrehab_source import publisher_inventory
    metadata_path = ROOT/spec['source_metadata']
    raw = metadata_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != spec['source_metadata_sha256']:
        raise ValueError('Publisher grant/inventory changed')
    inventory = publisher_inventory(json.loads(raw), json.loads(
        (metadata_path.parent/'version-http-receipt.json').read_bytes()), raw)
    expected = next(f for f in inventory if f['key'] == spec['file']['key'])
    if (expected['bytes'] != spec['file']['bytes'] or
            expected['publisher_checksum'] != 'md5:'+spec['file']['md5']):
        raise ValueError('Frozen file differs from the licensed publisher inventory')
    return spec


def fetch(spec):
    DATA.mkdir(parents=True, exist_ok=True)
    f = spec['file']
    target = DATA/f['key']
    receipt = DATA/'acquisition-receipt.json'
    if target.exists() or receipt.exists(): raise FileExistsError('Preserve first acquisition')
    md5, sha, total = hashlib.md5(), hashlib.sha256(), 0
    record = dict(url=f['url'], started_utc=datetime.now(timezone.utc).isoformat(),
                  protocol_sha256=hashlib.sha256(PROTOCOL.read_bytes()).hexdigest())
    try:
        with urllib.request.urlopen(urllib.request.Request(f['url'], headers={
                'User-Agent': 'Senpai-open-joint-measurement-audit/1'}), timeout=45) as response:
            record.update(http_status=response.status, headers=dict(response.headers))
            if response.status != 200: raise ValueError('Full original file response required')
            with target.open('xb') as out:
                while chunk := response.read(262144):
                    total += len(chunk)
                    if total > f['bytes']: raise ValueError('Publisher file exceeds frozen size')
                    md5.update(chunk); sha.update(chunk); out.write(chunk)
        if total != f['bytes'] or md5.hexdigest() != f['md5']:
            raise ValueError('Publisher payload size/checksum mismatch')
        record['verified'] = True
    except Exception as error:
        record.update(verified=False, error=repr(error))
        raise
    finally:
        record.update(bytes=total, md5=md5.hexdigest(), sha256=sha.hexdigest(),
                      finished_utc=datetime.now(timezone.utc).isoformat())
        receipt.write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'bytes': total, 'md5_verified': True, 'trajectories_not_yet_selected': True}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fetch', action='store_true')
    args = parser.parse_args()
    spec = protocol()
    if args.fetch:
        fetch(spec)
        return
    output = DATA/'selected-joint-schema.json'
    if output.exists(): raise FileExistsError('Preserve schema intake')
    raw = DATA/spec['file']['key']
    receipt = json.loads((DATA/'acquisition-receipt.json').read_bytes())
    if (receipt.get('verified') is not True or receipt['bytes'] != spec['file']['bytes'] or
            receipt['md5'] != spec['file']['md5'] or
            receipt['protocol_sha256'] != hashlib.sha256(PROTOCOL.read_bytes()).hexdigest()):
        raise ValueError('Verified prospective acquisition required')
    with raw.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != receipt['sha256']: raise ValueError('Acquired payload changed')
    with raw.open('rb') as stream:
        result = select_joint_frames(stream)
    # Decimal-to-string transport preserves original recorded numeric precision;
    # it is not an anatomical or confidence conversion. Raw payload stays intact.
    output.write_text(json.dumps(result, indent=2, default=str)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({k:v for k,v in result.items() if k not in {'frames','metadata_roster'}}))
    print('selected_frames', len(result['frames']), 'record_headers', len(result['metadata_roster']))


if __name__ == '__main__': main()
