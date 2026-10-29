"""Verify a frozen AndroidJUnitRunner inventory without losing on-device exports.

Manual instrumentation leaves the exact installed APK/evidence available to pull.
Each declared case needs one matching start and success; partial, failed, ignored,
duplicated or unpaired cases are refused, even when a final summary says OK.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path


def inventory(text, expected):
    expected = sorted(tuple(x) for x in expected)
    if not expected or len(set(expected)) != len(expected):
        raise ValueError('Missing or duplicated frozen identities')
    pending = None
    fields = {}
    passed = []
    final_codes = []
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                if key in fields and fields[key] != value:
                    raise ValueError('Conflicting status fields')
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            identity = (fields.get('class'), fields.get('test'))
            if not all(identity) or code not in {0, 1}:
                raise ValueError('Failed, skipped or unidentified case')
            if code == 1:
                if pending is not None or identity in passed:
                    raise ValueError('Duplicated or overlapping case start')
                pending = identity
            elif pending != identity:
                raise ValueError('Unpaired case success')
            else:
                passed.append(identity)
                pending = None
            fields = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            final_codes.append(int(line.partition(': ')[2]))
    if fields or pending is not None or sorted(passed) != expected:
        raise ValueError('Incomplete or changed case inventory')
    summaries = [int(x) for x in re.findall(r'^OK \((\d+) tests?\)\s*$', text, re.MULTILINE)]
    if summaries != [len(expected)] or final_codes != [-1]:
        raise ValueError('Missing or inconsistent successful runner termination')
    if re.search(r'FAILURES!!!|shortMsg=|Process crashed|INSTRUMENTATION_FAILED:', text):
        raise ValueError('Runner reports failure')
    return passed


def verify(spec, runtime, transcripts, apk_root):
    props = runtime['properties']
    if (spec['schema'] != 'senpai-preview-preference-runtime/1' or
        props['ro.build.version.sdk'] != str(spec['target_api']) or
        props['ro.build.fingerprint'] != spec['runtime_fingerprint'] or
        props['ro.product.cpu.abi'] != 'x86_64' or props['ro.kernel.qemu'] != '1' or
        props['sys.boot_completed'] != '1' or runtime['page_bytes'] != '4096' or
        runtime['serial'] != spec['serial']):
        raise ValueError('Actual runtime differs from frozen configuration')
    if set(transcripts) != set(spec['modules']):
        raise ValueError('Missing native module')
    counts = {m: len(inventory(text, spec['modules'][m]['test_ids'])) for m, text in transcripts.items()}
    for row in spec['apk_receipts']:
        if hashlib.sha256((apk_root/row['path']).read_bytes()).hexdigest() != row['sha256']:
            raise ValueError('APK differs from frozen candidate')
    return dict(schema='senpai-preview-preference-native-verification/1',
                verified_engine_commit=spec['candidate_commit'], api=spec['target_api'],
                emulator=True, page_bytes=4096, all_tests_pass=True, native_counts=counts,
                native_total=sum(counts.values()), performance_measured=False, human_rep_precision=None)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--protocol', type=Path, required=True)
    p.add_argument('--directory', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.output.exists():
        raise FileExistsError('Preserve prior verification')
    spec = json.loads(args.protocol.read_bytes())
    result = verify(spec, json.loads((args.directory/'runtime.json').read_bytes()),
                    {m: (args.directory/'native'/(m+'.txt')).read_text(encoding='utf-8') for m in spec['modules']},
                    Path(__file__).resolve().parents[1])
    result['protocol_sha256'] = hashlib.sha256(args.protocol.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
