"""Frozen development-only phase-specific veto; never opens validation/test.

Fits one fixed logistic candidate with subject-disjoint predictions. No score can
upgrade an engine result. A failed retention or readiness gate stops progression.
"""
from contextlib import ExitStack
import gzip
import hashlib
import json
from pathlib import Path
import zipfile
import joblib
import numpy as np
import sklearn
from fetch_uiprmd import ROOT, DATA, checksum
from inspect_uiprmd_development import read_development_member, parse_publisher_array
from evaluate_uiprmd_development import evaluate_rows, match_events, QUALIFIED
from train_uiprmd_quality_heads import model, split_indices, evaluate_veto
from uiprmd_quality_features import frame_features
from uiprmd_phase_quality import SCHEMA, phase_features, verify_development_cohort

PROTOCOL = ROOT / 'models/uiprmd-phase-quality-protocol.json'


def frozen_protocol():
    protocol = json.loads(PROTOCOL.read_bytes())
    if (protocol['schema'] != 'uiprmd-phase-quality-protocol/1' or
            protocol['allow_validation_access'] or protocol['allow_final_test_access'] or
            protocol['feature_schema'] != SCHEMA or protocol['candidate'] != 'logistic_C0_1' or
            protocol['veto_bad_score_threshold'] != .75):
        raise PermissionError('Frozen development-only protocol required')
    for receipt in protocol['receipts']:
        path = (ROOT / receipt['path']).resolve()
        if not path.is_relative_to(ROOT.resolve()):
            raise ValueError('Receipt escaped workspace')
        raw = path.read_bytes()
        if 'sha256_lf' in receipt:
            raw = raw.replace(b'\r\n', b'\n')
        if hashlib.sha256(raw).hexdigest() != receipt.get('sha256_lf', receipt.get('sha256')):
            raise ValueError('Frozen source or input changed: ' + str(path))
    return protocol


def compact(report):
    return {k: v for k, v in report.items() if k != 'rep_results'}


def main():
    protocol = frozen_protocol()
    directory = DATA / 'phase-quality-development'
    output = directory / 'result.json'
    if output.exists():
        raise FileExistsError('Preserve the frozen result; do not refit or sweep thresholds')
    baseline_path = ROOT / protocol['baseline_replay']
    baseline = json.loads(baseline_path.read_bytes())
    if baseline['compiler'] != 'qualified-rep-compiler/7':
        raise ValueError('Expected verified current compiler')
    verify_development_cohort(baseline['rows'])
    manifest = json.loads((directory / 'manifest.json').read_bytes())
    if (manifest['scope'] != 'development' or manifest['compiler'] != baseline['compiler'] or
            manifest['final_test_trajectories_opened'] or manifest['production_acceptance_policy_changed']):
        raise PermissionError('Only passive current-engine development evidence is permitted')
    entries = {(r['subject'], r['movement'], r['correct']): r for r in manifest['records']}
    if len(entries) != 16 or len(manifest['records']) != 16:
        raise ValueError('Complete unique evidence cohort required')
    acquisition = json.loads((DATA / 'acquisition.json').read_bytes())
    samples, reads, names = [], [], None
    with ExitStack() as stack:
        archives = {}
        for name in ('Movements.zip', 'Incorrect Movements.zip'):
            receipt = next(r for r in acquisition['files'] if r['file'] == name)
            if checksum(DATA / name, 'sha256') != receipt['sha256']:
                raise ValueError('Publisher archive changed')
            archives[name] = stack.enter_context(zipfile.ZipFile(DATA / name))
        for i, row in enumerate(baseline['rows']):
            entry = entries[(row['subject'], row['movement'], row['correct'])]
            path = (directory / entry['file']).resolve()
            if path.parent != directory.resolve() or checksum(path, 'sha256') != entry['sha256']:
                raise ValueError('Passive phase evidence changed')
            with gzip.open(path, 'rt', encoding='utf-8') as stream:
                record = json.load(stream)
            for key in ('subject', 'movement', 'correct', 'source_sha256', 'reference_subject', 'reference_sha256', 'reference_key'):
                if record[key] != row[key]:
                    raise ValueError('Passive source/reference identity changed')
            if (record['attempts'] != row['attempts'] or record['episodes'] != row['episodes']):
                # Passive evidence exports a strict subset of the engine attempt fields.
                for a, b in zip(record['attempts'], row['attempts']):
                    if any(a[k] != b[k] for k in a):
                        raise ValueError('Diagnostic engine changed an attempt')
                if len(record['attempts']) != len(row['attempts']) or record['episodes'] != row['episodes']:
                    raise ValueError('Original continuous episodes changed')
            if not record['comparison_outputs_identical_with_and_without_observer']:
                raise ValueError('Passive observer affected inference')
            name = 'Movements.zip' if row['correct'] else 'Incorrect Movements.zip'
            raw = read_development_member(archives[name], row['source_member'], {'01', '02', '03', '04'})
            if hashlib.sha256(raw).hexdigest() != row['source_sha256']:
                raise ValueError('Original marker recording changed')
            reads.append(dict(archive=name, member=row['source_member'], sha256=row['source_sha256']))
            array, _ = parse_publisher_array(raw, 'Vicon')
            values = frame_features(array)
            matches, _ = match_events(row['episodes'], row['attempts'])
            labels = {match['attempt_index']: not row['episodes'][rep]['correct'] for rep, match in matches.items()}
            for j, attempt in enumerate(row['attempts']):
                evidence = [s for s in record['samples'] if s['attempt_id'] == attempt['attempt_id']]
                vector, current_names = phase_features(values, evidence, attempt['started_ms'], attempt['completed_ms'])
                if names is not None and current_names != names:
                    raise ValueError('Feature order changed')
                names = current_names
                samples.append(dict(row=i, attempt=j, subject=row['subject'], movement=row['movement'],
                    label_bad=labels.get(j), vector=vector))
    x = np.asarray([s['vector'] for s in samples])
    scores = np.full(len(samples), np.nan)
    folds, fits = [], []
    for movement in ('m01', 'm07'):
        selected = np.flatnonzero([s['movement'] == movement for s in samples])
        subjects = np.asarray([samples[i]['subject'] for i in selected])
        for subject in ('01', '02', '03', '04'):
            a, b = split_indices(subjects, subject)
            train = np.asarray([selected[k] for k in a if samples[selected[k]]['label_bad'] is not None])
            test = selected[b]
            classifier = model(protocol['candidate'])
            classifier.fit(x[train], [int(samples[i]['label_bad']) for i in train])
            scores[test] = classifier.predict_proba(x[test])[:, list(classifier.classes_).index(1)]
            model_path = directory / f'{movement}-held-{subject}.joblib'
            if model_path.exists():
                raise FileExistsError('Preserve prior fold model')
            joblib.dump(classifier, model_path, compress=3)
            fits.append(dict(path=model_path.relative_to(ROOT).as_posix(), sha256=checksum(model_path, 'sha256')))
            folds.append(dict(movement=movement, held_subject=subject,
                train_subjects=sorted({samples[i]['subject'] for i in train}), train_attempts=len(train),
                inference_attempts=len(test), training_includes_only_matched_original_labels=True))
    candidate_rows = json.loads(json.dumps(baseline['rows']))
    changes = []
    for sample, score in zip(samples, scores):
        if not np.isfinite(score):
            raise ValueError('Every emitted attempt requires one out-of-fold prediction')
        attempt = candidate_rows[sample['row']]['attempts'][sample['attempt']]
        if attempt['verdict'] in QUALIFIED and score >= protocol['veto_bad_score_threshold']:
            changes.append(dict(row=sample['row'], attempt=sample['attempt'], subject=sample['subject'],
                movement=sample['movement'], matched_label_bad=sample['label_bad'], original_verdict=attempt['verdict'],
                started_ms=attempt['started_ms'], completed_ms=attempt['completed_ms'], bad_score=float(score)))
            attempt['verdict'] = 'REJECTED'
    before, after = evaluate_rows(baseline['rows']), evaluate_rows(candidate_rows)
    if before['counts']['total_reps'] != 160 or after['counts']['total_reps'] != 160:
        raise ValueError('Frozen denominator changed')
    retained = not any(c['matched_label_bad'] is False for c in changes)
    criteria = protocol['evaluation']
    numeric_pass = (after['precision'] is not None and after['precision'] >= criteria['precision'] and
        after['good_rep_recall'] >= criteria['good_rep_recall'] and after['assessable_coverage'] >= criteria['coverage'])
    result = dict(schema='uiprmd-phase-quality-development/1',
        kind='development-skeletal-completed-attempt-veto-not-camera-accuracy',
        decision='REJECTED_AFTER_DEVELOPMENT' if not (retained and numeric_pass) else 'NEEDS_SEPARATELY_FROZEN_VALIDATION',
        baseline=compact(before), candidate=compact(after), previously_counted_good_retained=retained,
        passes_numeric_development_gates=numeric_pass, changes=changes,
        feature_schema=SCHEMA, feature_names=names, feature_count=len(names), folds=folds, fitted_models=fits,
        all_attempts_scored_including_unmatched=len(samples), supervised_matched_attempts=sum(s['label_bad'] is not None for s in samples),
        scores=[dict(row=s['row'], attempt=s['attempt'], bad_score_uncalibrated=float(p)) for s, p in zip(samples, scores)],
        reads=reads, source_replay_sha256=checksum(baseline_path, 'sha256'),
        passive_manifest_sha256=checksum(directory/'manifest.json', 'sha256'),
        protocol_sha256=checksum(PROTOCOL, 'sha256'), sklearn_version=sklearn.__version__, numpy_version=np.__version__,
        validation_subjects_opened=False, final_subjects_opened=False, production_changed=False,
        raw_network_weights_changed=False, model_promoted=False, human_live_rep_precision=None, readiness_points_earned=0,
        recall_ceiling_without_upgrades=before['good_rep_recall'],
        limitations=['Completed-attempt labels cannot locate fault onset or supervise frame-level coaching.',
            'Fixed clinical marker variants and binary observability are separate from MP33 camera confidence.',
            'Phase features consume only original engine samples at or before causal completion.',
            'Imputation/scaling fit the three training subjects only; missing marker geometry remains missing.',
            'A veto cannot recover withheld good repetitions or meet recall above the existing engine ceiling.',
            'Development choices and previously inspected reference subjects do not constitute final held-out validation.'])
    output.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: result[k] for k in ('decision', 'baseline', 'candidate', 'previously_counted_good_retained',
        'passes_numeric_development_gates', 'feature_count', 'all_attempts_scored_including_unmatched')}, indent=2))


if __name__ == '__main__':
    main()
