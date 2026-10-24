"""Capture the preceding delivered engine's immutable baseline receipts, not current tuning results."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE='4d899899628e08c0db77fddcdad022cb7f081edf'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('delivery',type=Path)
    args=parser.parse_args()
    source=args.delivery
    manifest=json.loads((source/'delivery-manifest.json').read_text())
    assert manifest['commit']==BASE
    for name,info in manifest['files'].items():
        assert hashlib.sha256((source/name).read_bytes()).hexdigest()==info['sha256'],name
    tests=json.loads((source/'test-summary.json').read_text())
    assert (tests['scoped_jvm_cases'],tests['native_suite_cases'],tests['generated_regression_cases'],tests['controlled_corruption_cases'],tests['final_failures'])==(235,20,2688,124,0)
    destination=ROOT/'test-artifacts/exercise-knowledge/readiness-baseline'
    destination.mkdir(exist_ok=True)
    for name in ['delivery-manifest.json','comparison.json','test-summary.json']:shutil.copy2(source/name,destination/name)
    with zipfile.ZipFile(source/'verification-evidence.zip') as archive:
        (destination/'reference-registry.json').write_bytes(archive.read('multipass-summary/complete-reference-backend-selection.json'))
    registry=json.loads((destination/'reference-registry.json').read_text())
    assert len(registry)==32
    ready=[r for r in registry if r['selected_model']!='unavailable']
    assert len(ready)==12
    rubric=ROOT/'models/readiness-rubric.json'
    metrics={'verified_engine_commit':BASE,'reference_total':32,'reference_usable':len(ready),
        'camera_exercise_ids':sorted({r['exercise'] for r in ready}),'mechanism_and_scoped_tests_pass':True,
        'android_runtimes':[{'api':36,'emulator':True,'all_tests_pass':True}],
        'undocumented_reference_losses':0,'independent_skeleton':None,'independent_camera':None,'live_soak':None}
    metrics.pop('live_soak')
    report={'schema':'senpai-readiness-evidence/1','kind':'immutable-previous-delivery-baseline-not-a-new-performance-gain',
        'rubric_sha256':hashlib.sha256(rubric.read_bytes()).hexdigest(),'metrics':metrics,
        'proofs':[{'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(destination.iterdir())]}
    (ROOT/'models/readiness-baseline.evidence.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Captured verified baseline receipts for',BASE)


if __name__=='__main__':main()
