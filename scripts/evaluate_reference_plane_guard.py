"""Fixed reference-only plane guard; validation is selection data, final test forbidden."""
import argparse
import copy
import hashlib
import json
import subprocess
import zipfile
from datetime import datetime, timezone
from fetch_uiprmd import DATA, ROOT, checksum
from analyze_uiprmd_evidence import read_scope
from evaluate_uiprmd_development import evaluate_rows, QUALIFIED
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from prepare_uiprmd_validation import read_validation_member
from uiprmd_quality_features import frame_features
from uiprmd_reference_plane_guard import SCHEMA, POLICY, fit_reference, evaluate_attempt

PROTOCOL = ROOT/'models/uiprmd-plane-guard-protocol.json'
MODELS = DATA/'plane-guard-reference-models-v2.json'


def guarded_read(archive, record, scope):
    if scope not in {'development', 'validation'}:
        raise PermissionError('No final-test access')
    intent = {'scope': scope, 'member': record['source_member'], 'utc': datetime.now(timezone.utc).isoformat()}
    with (DATA/'plane-guard-source-access.jsonl').open('a') as log:
        log.write(json.dumps(intent | {'status': 'requested'})+'\n'); log.flush()
        raw = (read_validation_member(archive, record['source_member']) if scope == 'validation' else
               read_development_member(archive, record['source_member'], {'01', '02', '03', '04'}))
        digest = hashlib.sha256(raw).hexdigest()
        if digest != record['source_sha256']:
            raise ValueError('Original marker data changed')
        log.write(json.dumps(intent | {'status': 'read', 'sha256': digest})+'\n')
    return parse_publisher_array(raw, 'Vicon')[0]


def fit_models():
    reference_path = DATA/'plane-reference-inputs.json'
    inputs = json.loads(reference_path.read_text())
    if (inputs['schema'] != 'uiprmd-plane-reference-inputs/1' or inputs['compiler'] != 'qualified-rep-compiler/5' or
            inputs['validation_trajectories_opened'] or inputs['final_test_trajectories_opened'] or
            inputs['development_manifest_sha256'] != checksum(DATA/'development-vicon/manifest.json', 'sha256')):
        raise ValueError('Reference scope changed')
    if len(inputs['references']) != 2 or {r['subject'] for r in inputs['references']} != {'01', '02'}:
        raise ValueError('Only fixed development references 01/02 are permitted')
    models = {}
    with zipfile.ZipFile(DATA/'Movements.zip') as archive:
        for record in inputs['references']:
            array = guarded_read(archive, record, 'development')
            plane = frame_features(array)['angle.right_arm_out_of_torso_plane']
            models[record['subject']] = fit_reference(record['samples'], plane) | {
                'reference_source_sha256': record['source_sha256'], 'reference_key': record['reference_key'],
                'source_file': record['source_member'], 'side': 'RIGHT'}
    result = {'schema': SCHEMA, 'policy': POLICY, 'kind': 'reference-only-skeletal-plane-bands-not-network-training',
              'reference_inputs_sha256': checksum(reference_path, 'sha256'), 'models': models}
    if MODELS.exists():
        raise FileExistsError('Preserve existing reference model freeze; do not overwrite it')
    MODELS.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')


def frozen_protocol():
    protocol = json.loads(PROTOCOL.read_text())
    if not protocol['validation_evaluation_authorized']:
        raise PermissionError('Rejected development candidate cannot open validation trajectories')
    committed = json.loads(subprocess.check_output(['git', 'show', 'HEAD:models/uiprmd-plane-guard-protocol.json'], cwd=ROOT))
    if (protocol != committed or protocol['schema'] != 'uiprmd-plane-guard-protocol/1' or
            protocol['allow_final_test_access'] or protocol['allow_validation_refit'] or protocol['policy'] != POLICY):
        raise ValueError('Candidate is not committed or its frozen scope changed')
    for path, digest in protocol['source_sha256'].items():
        if checksum(ROOT/path, 'sha256') != digest:
            raise ValueError('Frozen experiment source changed: '+path)
    if checksum(MODELS, 'sha256') != protocol['reference_models_sha256']:
        raise ValueError('Frozen reference models changed')
    return protocol


def apply_guard(attempt, guard):
    result = copy.deepcopy(attempt)
    if attempt['verdict'] not in QUALIFIED:
        return result
    if guard['status'] == 'REJECTED':
        result['verdict'] = 'REJECTED'
        result['reasons'] = list(result['reasons'])+['REFERENCE_ARM_PLANE_DEVIATION']
    elif guard['status'] == 'UNASSESSABLE':
        result['verdict'] = 'UNASSESSABLE'
        result['reasons'] = list(result['reasons'])+[guard['reason']]
    return result


def run(scope):
    if scope not in {'development', 'validation'}:
        raise PermissionError('Final-test access forbidden')
    protocol = frozen_protocol() if scope == 'validation' else None
    manifest_path, records = read_scope(scope)
    if protocol is not None and checksum(manifest_path, 'sha256') != protocol['validation_evidence_manifest_sha256']:
        raise ValueError('Validation export changed')
    model_file = json.loads(MODELS.read_text())
    if model_file['schema'] != SCHEMA or model_file['policy'] != POLICY:
        raise ValueError('Reference policy changed')
    candidate = copy.deepcopy(records); traces = []
    with zipfile.ZipFile(DATA/'Movements.zip') as good, zipfile.ZipFile(DATA/'Incorrect Movements.zip') as bad:
        for record, output in zip(records, candidate):
            if record['movement'] != 'm07':
                continue
            array = guarded_read(good if record['correct'] else bad, record, scope)
            plane = frame_features(array)['angle.right_arm_out_of_torso_plane']
            reference = model_file['models'][record['reference_subject']]
            if reference['reference_key'] != record['reference_key'] or reference['reference_source_sha256'] != record['reference_sha256']:
                raise ValueError('Source outcomes cannot choose the reference')
            for i, attempt in enumerate(record['attempts']):
                if attempt['verdict'] not in QUALIFIED:
                    continue
                samples = [s for s in record['samples'] if s['attempt_id'] == attempt['attempt_id']]
                if any(not attempt['started_ms'] <= s['timestamp_ms'] <= attempt['completed_ms'] for s in samples):
                    raise ValueError('Only a causal attempt prefix may be evaluated')
                result = evaluate_attempt(samples, plane, reference)
                output['attempts'][i] = apply_guard(attempt, result)
                traces.append({'subject': record['subject'], 'movement': record['movement'], 'record_correct': record['correct'],
                               'attempt_id': attempt['attempt_id'], 'before': attempt['verdict'],
                               'after': output['attempts'][i]['verdict'], 'guard': result})
    baseline = evaluate_rows(records); measured = evaluate_rows(candidate)
    if any(m['counts']['total_reps'] != (160 if scope == 'development' else 120) for m in [baseline, measured]):
        raise ValueError('Original label denominators changed')
    def index(metrics):
        return {(r['subject'], r['movement'], r['correct'], r['episode']): r['verdict'] for r in metrics['rep_results']}
    old, new = index(baseline), index(measured)
    passes = (measured['precision'] is not None and measured['precision'] >= .90 and
              measured['good_rep_recall'] >= .70 and measured['assessable_coverage'] >= .80)
    report = {'schema': 'uiprmd-plane-guard-evaluation/1', 'scope': scope, 'kind': 'skeletal-selection-research-not-final-test-or-camera-precision',
              'policy': POLICY, 'reference_models_sha256': checksum(MODELS, 'sha256'), 'baseline': baseline, 'candidate': measured,
              'good_counts_lost': sum(k[2] and old[k] in QUALIFIED and new[k] not in QUALIFIED for k in old),
              'incorrect_counts_removed': sum(not k[2] and old[k] in QUALIFIED and new[k] not in QUALIFIED for k in old),
              'passes_unchanged_validation_criteria': passes, 'promotion_approved': False,
              'readiness_points_earned': 0, 'final_test_subjects_opened': False, 'raw_mp33_modified': False,
              'new_human_annotations': False, 'source_labels_select_bands': False, 'refitted_on_validation': False,
              'traces': traces, 'source_manifest_sha256': checksum(manifest_path, 'sha256')}
    if protocol is not None:
        report['candidate_protocol_sha256'] = checksum(PROTOCOL, 'sha256')
        report['candidate_freeze_commit'] = subprocess.check_output(['git', 'log', '-1', '--format=%H', '--', 'models/uiprmd-plane-guard-protocol.json'], cwd=ROOT, text=True).strip()
    (DATA/f'plane-guard-v2-{scope}.json').write_text(json.dumps(report, indent=2, allow_nan=False)+'\n')
    print(json.dumps({k: ({a: b for a, b in report[k].items() if a != 'rep_results'} if k in {'baseline', 'candidate'} else report[k])
                      for k in ['scope', 'baseline', 'candidate', 'good_counts_lost', 'incorrect_counts_removed', 'passes_unchanged_validation_criteria']}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scope', required=True, choices=['development', 'validation'])
    parser.add_argument('--fit-references', action='store_true')
    args = parser.parse_args()
    if args.fit_references:
        if args.scope != 'development':
            raise PermissionError('Validation cannot fit reference bands')
        fit_models()
    run(args.scope)


if __name__ == '__main__':
    main()
