"""Bounded publisher-metadata intake; never opens ratings, trajectories or media.

The concept record can fail while its explicitly linked version is available.
Neither an open data grant nor a joint-angle benchmark supplies binary whole-rep
truth. This audit records those separate facts and cannot qualify an evaluation.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'test-artifacts/exercise-knowledge/ucophyrehab-source-review-20261029'
VERSION = 17935737
CONCEPT = 17935736
URL = f'https://zenodo.org/api/records/{VERSION}'


def concept_observation(directory):
    """Preserve a failed concept response without calling its version deleted."""
    raw = (directory / 'zenodo-record.json').read_bytes()
    receipt = json.loads((directory / 'http-receipts.json').read_bytes())['receipts']
    if len(receipt) != 1:
        raise ValueError('One original concept observation required')
    r = receipt[0]
    if (r.get('path') != 'zenodo-record.json' or
            r.get('url') != f'https://zenodo.org/api/records/{CONCEPT}' or
            r.get('sha256') != hashlib.sha256(raw).hexdigest() or
            r.get('bytes') != len(raw) or type(r.get('http_status')) is not int or
            json.loads(raw).get('status') != r['http_status']):
        raise ValueError('Concept response does not match its HTTP receipt')
    return r['http_status'], r['sha256']


def publisher_inventory(record, receipt, raw):
    """Require the actual version's data grant and hash-bound metadata response."""
    if (receipt.get('http_status') != 200 or receipt.get('url') != URL or
            receipt.get('sha256') != hashlib.sha256(raw).hexdigest() or
            receipt.get('bytes') != len(raw)):
        raise ValueError('Version response does not match its HTTP receipt')
    if json.loads(raw) != record:
        raise ValueError('Parsed record does not match captured response')
    metadata = record.get('metadata', {})
    if (record.get('id') != VERSION or metadata.get('doi') != f'10.5281/zenodo.{VERSION}' or
            str(record.get('conceptrecid')) != str(CONCEPT)):
        raise ValueError('Unexpected publisher version/concept identity')
    if metadata.get('access_right') != 'open' or metadata.get('license', {}).get('id') != 'cc-by-4.0':
        raise ValueError('This intake requires the version record explicit CC BY 4.0 data grant')
    files = record.get('files', [])
    names = [f.get('key') for f in files]
    if not files or any(not isinstance(n, str) or not n for n in names) or len(set(names)) != len(names):
        raise ValueError('Missing or duplicated publisher file identities')
    result = []
    for f in files:
        digest = f.get('checksum', '')
        if (type(f.get('size')) is not int or f['size'] <= 0 or
                not isinstance(digest, str) or not digest.startswith('md5:') or
                len(digest) != 36 or any(c not in '0123456789abcdef' for c in digest[4:])):
            raise ValueError('Missing publisher file size/checksum')
        result.append({'key': f['key'], 'bytes': f['size'], 'publisher_checksum': digest,
                       'payload_downloaded_or_checksum_verified': False})
    return sorted(result, key=lambda f: f['key'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=DATA)
    args = parser.parse_args()
    directory = args.directory
    output = directory / 'publisher-intake-verified.json'
    if output.exists():
        raise FileExistsError('Preserve the original source intake')
    raw = (directory / 'zenodo-version-record.json').read_bytes()
    record = json.loads(raw)
    receipt = json.loads((directory / 'version-http-receipt.json').read_bytes())
    inventory = publisher_inventory(record, receipt, raw)
    concept_status, concept_sha = concept_observation(directory)
    report = dict(
        schema='ucophyrehab-publisher-intake/1', source_url=URL,
        concept_record_http_status=concept_status, concept_response_sha256=concept_sha,
        version_record_http_status=receipt['http_status'],
        version_response_sha256=hashlib.sha256(raw).hexdigest(),
        version_data_license='CC-BY-4.0', article_or_code_license_used_as_data_grant=False,
        concept_http_failure_does_not_override_verified_version=True,
        file_inventory=inventory,
        scope_basis='publisher descriptor and file inventory; annotation payload schema not yet audited',
        descriptor_score_scope='two-rater ordinal repetition scores; not binary whole-rep correctness',
        descriptor_3d_scope='one articulation per exercise; not whole-body or MP33 truth',
        public_derived_modalities_not_original_RGB=True,
        sample_archive_subject_count_not_a_three_subject_test=True,
        binary_labels_inferred_from_ordinal_ratings=False,
        original_RGB_access_requested=False, dataset_payloads_opened=False,
        ratings_or_trajectories_opened=False, new_human_labels=0,
        final_uiprmd_subjects_opened=False, model_trained=False, production_changed=False,
        independent_evaluation_qualified=False, human_live_rep_precision=None,
        readiness_points_earned=0,
        next_admissible_use='Prospectively freeze a joint-measurement-only study before opening data; verify actual schema, synchronisation and exercise scope. No whole-form or RGB precision claim.')
    output.write_text(json.dumps(report, indent=2, allow_nan=False)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'files': len(inventory), 'data_grant': report['version_data_license'],
                      'evaluation_qualified': False, 'readiness_points_earned': 0}))


if __name__ == '__main__':
    main()
