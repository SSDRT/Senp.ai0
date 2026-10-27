"""Compare a second Android runtime with an immutable native-suite inventory.

Functional runtime evidence only: no performance, pose accuracy or device-family
claim is inferred from a green suite. The protocol must predate the new run.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def native_inventory(paths):
    """Require every declared test to be present, unique and successful."""
    names = []
    if not paths:
        raise ValueError('Missing native XML results')
    for path in paths:
        root = ET.parse(path).getroot()
        cases = root.findall('testcase')
        if root.tag != 'testsuite' or int(root.get('tests', '-1')) != len(cases):
            raise ValueError('Declared native count differs from recorded cases')
        if any(int(root.get(k, '-1')) != 0 for k in ('failures', 'errors', 'skipped')):
            raise ValueError('Native suite failed or skipped cases')
        for case in cases:
            if any(case.find(k) is not None for k in ('failure', 'error', 'skipped')):
                raise ValueError('Native case failed or skipped')
            name = (case.get('classname'), case.get('name'))
            if not all(name) or name in names:
                raise ValueError('Missing or duplicated native test identity')
            names.append(name)
    return sorted(names)


def verify_runtime(protocol, runtime, results, apk_root=ROOT):
    if protocol['schema'] != 'senpai-second-runtime-protocol/1':
        raise ValueError('Unknown frozen runtime protocol')
    expected_api = protocol['target_api']
    if expected_api == protocol['existing_api']:
        raise ValueError('A duplicate API cannot qualify as a second runtime')
    props = runtime['properties']
    if (props['ro.build.version.sdk'] != str(expected_api) or
            props['ro.kernel.qemu'] != '1' or props['sys.boot_completed'] != '1' or
            props['ro.product.cpu.abi'] != protocol['abi'] or
            not runtime['serial'].startswith('emulator-')):
        raise ValueError('Actual booted runtime does not match the frozen target')
    page_bytes = int(runtime['page_bytes'])
    if page_bytes not in (4096, 16384):
        raise ValueError('Unrecognised observed page size')
    if set(results) != set(protocol['modules']):
        raise ValueError('Missing or extra native module')
    counts = {}
    for module, paths in results.items():
        observed = native_inventory(paths)
        expected = sorted(tuple(row) for row in protocol['modules'][module]['test_ids'])
        if observed != expected:
            raise ValueError(f'Native suite identities changed: {module}')
        counts[module] = len(observed)
    for receipt in protocol['apk_receipts']:
        if sha256(apk_root / receipt['path']) != receipt['sha256']:
            raise ValueError('APK differs from the frozen tested implementation')
    return dict(schema='senpai-second-runtime-verification/1',
                kind='functional-emulator-runtime-not-human-pose-accuracy',
                verified_engine_commit=protocol['verified_engine_commit'],
                api=expected_api, abi=props['ro.product.cpu.abi'], page_bytes=page_bytes,
                emulator=True, physical=False, all_tests_pass=True, native_counts=counts,
                native_total=sum(counts.values()), actual_live_rep_precision=None,
                sustained_performance_measured=False,
                independent_final_subjects_opened=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True,
                        help='Directory with one subdirectory per frozen module')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Preserve existing runtime verification')
    protocol = json.loads(args.protocol.read_text())
    results = {m: sorted((args.results / m).rglob('TEST-*.xml')) for m in protocol['modules']}
    report = verify_runtime(protocol, json.loads(args.runtime.read_text()), results)
    report['protocol_sha256'] = sha256(args.protocol)
    report['runtime_sha256'] = sha256(args.runtime)
    report['native_xml_receipts'] = [dict(module=m, path=p.relative_to(args.results).as_posix(), sha256=sha256(p))
                                   for m, paths in results.items() for p in paths]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
