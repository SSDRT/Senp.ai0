import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from audit_ucophyrehab_source import publisher_inventory, concept_observation, URL, VERSION, CONCEPT
from evaluate_uiprmd_development import evaluate_rows


class UcophyrehabSourceTests(unittest.TestCase):
    def fixture(self):
        record = {'id': VERSION, 'conceptrecid': str(CONCEPT), 'metadata': {
            'doi': f'10.5281/zenodo.{VERSION}', 'access_right': 'open',
            'license': {'id': 'cc-by-4.0'}}, 'files': [
                {'key': 'declared.jsonl', 'size': 100, 'checksum': 'md5:'+'0'*32}]}
        return record

    def run_record(self, record, **changes):
        raw = json.dumps(record).encode()
        receipt = dict(url=URL, http_status=200, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        receipt.update(changes)
        return publisher_inventory(record, receipt, raw)

    def test_version_can_be_verified_without_inheriting_concept_endpoint_failure(self):
        result = self.run_record(self.fixture())
        self.assertEqual('declared.jsonl', result[0]['key'])
        self.assertFalse(result[0]['payload_downloaded_or_checksum_verified'])

    def test_article_or_code_grants_cannot_replace_version_data_grant(self):
        for licence in [None, {}, {'id': 'cc-by-nc-nd-4.0'}, {'id': 'mit'}]:
            r = self.fixture(); r['metadata']['license'] = licence or {}
            with self.subTest(licence=licence), self.assertRaises(ValueError):
                self.run_record(r)

    def test_response_must_match_observed_url_status_hash_and_size(self):
        for changed in [dict(url=URL+'0'), dict(http_status=410), dict(sha256='0'*64), dict(bytes=0)]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.run_record(self.fixture(), **changed)

    def test_version_doi_and_concept_must_agree(self):
        for part in ['id', 'doi', 'conceptrecid']:
            r = self.fixture()
            if part == 'doi': r['metadata']['doi'] = f'10.5281/zenodo.{CONCEPT}'
            else: r[part] = 99
            with self.subTest(part=part), self.assertRaises(ValueError): self.run_record(r)

    def test_inventory_rejects_duplicate_or_unbound_files(self):
        for change in ['duplicate', 'checksum', 'size']:
            r = self.fixture()
            if change == 'duplicate': r['files'].append(copy.deepcopy(r['files'][0]))
            elif change == 'checksum': r['files'][0]['checksum'] = 'md5:'+'z'*32
            else: r['files'][0]['size'] = True
            with self.subTest(change=change), self.assertRaises(ValueError): self.run_record(r)

    def test_ordinal_scores_are_not_truthy_binary_correctness(self):
        for rating in [1, 5, 4.5, [4, 5], {'rater_1': 4, 'rater_2': 5}]:
            row = {'subject': 'development', 'movement': 'joint-only', 'attempts': [],
                   'episodes': [{'episode': 1, 'start_ms': 0, 'end_ms': 990, 'correct': rating}]}
            with self.subTest(rating=rating), self.assertRaisesRegex(ValueError, 'boolean correctness'):
                evaluate_rows([row])

    def test_concept_failure_is_separately_hash_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            raw = b'{"status":410,"message":"The record has been deleted."}'
            (directory/'zenodo-record.json').write_bytes(raw)
            receipt = dict(path='zenodo-record.json', url=f'https://zenodo.org/api/records/{CONCEPT}',
                           bytes=len(raw), http_status=410, sha256=hashlib.sha256(raw).hexdigest())
            (directory/'http-receipts.json').write_text(json.dumps({'receipts': [receipt]}))
            self.assertEqual((410, receipt['sha256']), concept_observation(directory))
            (directory/'zenodo-record.json').write_bytes(raw+b' ')
            with self.assertRaisesRegex(ValueError, 'Concept response'):
                concept_observation(directory)


if __name__ == '__main__':
    unittest.main()
