"""Offline source-eligibility audit. Metadata/rights checks never award pose points.

No downloader, annotation inference, camera-evidence inference or engine mutation
is performed. A licensed candidate still needs original-frame/model calibration.
"""
import argparse
import hashlib
from html.parser import HTMLParser
import json
import math
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'test-artifacts/exercise-knowledge/glute-bridge-source-review'
# Publisher exercise 265 explicitly describes bilateral floor bridges. Exercise
# 294 is bench-supported hip thrust; 1906 describes unilateral leg extension.
# Neither is silently substituted for this declared reference variant.
DECLARED_EXERCISES = {265: ('glute_bridge', 'bilateral-floor-glute-bridge')}
OPEN_LICENSES = {
    ('creativecommons.org', '/licenses/by/3.0/'),
    ('creativecommons.org', '/licenses/by/4.0/'),
    ('creativecommons.org', '/licenses/by-sa/3.0/'),
    ('creativecommons.org', '/licenses/by-sa/4.0/'),
    ('creativecommons.org', '/publicdomain/zero/1.0/'),
}


class VisibleText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in {'script', 'style'}:
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in {'script', 'style'}:
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def asset_rights(html):
    parser = VisibleText()
    parser.feed(html)
    text = ' '.join(' '.join(parser.parts).casefold().split())
    # The asset-specific warning takes priority over a general public-domain banner.
    if ('asset contains copyrighted material' in text or
            'portions of the asset' in text and 'not licensed for distribution' in text):
        return 'REJECT_ASSET_SPECIFIC_COPYRIGHT_RESTRICTION'
    if 'public domain' in text:
        return 'PUBLIC_DOMAIN_BANNER_REQUIRES_ASSET_AND_SCOPE_VERIFICATION'
    return 'NO_VERIFIED_ASSET_LICENSE'


def licensed_video_candidate(video, licenses, exercises):
    exercise = video.get('exercise')
    if type(exercise) is not int or exercise not in DECLARED_EXERCISES:
        return 'OUTSIDE_DECLARED_VARIANT'
    if exercise not in exercises:
        return 'MISSING_PUBLISHER_EXERCISE'
    license_id = video.get('license')
    license_record = licenses.get(license_id) if type(license_id) is int else None
    if license_record is None:
        return 'MISSING_ASSET_LICENSE'
    url = urlsplit(license_record.get('url', ''))
    path = url.path.removesuffix('deed.en').rstrip('/') + '/'
    if (url.scheme not in {'https', 'http'} or url.username or url.password or
            (url.hostname, path) not in OPEN_LICENSES):
        return 'ASSET_LICENSE_NOT_IN_OPEN_ALLOWLIST'
    if not isinstance(video.get('license_author'), str) or not video['license_author'].strip():
        return 'MISSING_ASSET_ATTRIBUTION'
    media = urlsplit(video.get('video', ''))
    if (media.scheme != 'https' or media.hostname != 'wger.de' or media.username or media.password or
            not media.path.startswith('/media/exercise-video/')):
        return 'UNVERIFIED_PUBLISHER_MEDIA_URL'
    try:
        duration = float(video['duration'])
    except (TypeError, ValueError, KeyError):
        return 'INVALID_PUBLISHER_DURATION'
    if not math.isfinite(duration) or duration <= 0:
        return 'INVALID_PUBLISHER_DURATION'
    return 'LICENSED_CANDIDATE_REQUIRES_ORIGINAL_VIDEO_QA_AND_CALIBRATION'


def audit_catalogue(videos, licenses, exercises):
    for payload in (videos, licenses, exercises):
        if payload.get('next') is not None or payload.get('count') != len(payload.get('results', [])):
            raise ValueError('Incomplete publisher metadata: do not infer absence from one page')
    for payload in (videos, licenses, exercises):
        ids = [row['id'] for row in payload['results']]
        if len(ids) != len(set(ids)):
            raise ValueError('Duplicated publisher metadata identity')
    exercise_map = {x['id']: x for x in exercises['results']}
    license_map = {x['id']: x for x in licenses['results']}
    rows = []
    for video in videos['results']:
        status = licensed_video_candidate(video, license_map, exercise_map)
        rows.append(dict(video_id=video['id'], exercise_id=video.get('exercise'), status=status))
    return dict(publisher_exercises=exercises['count'], publisher_videos=videos['count'],
                declared_exercises=[dict(exercise_id=e, policy=p, variant=v,
                    linked_videos=[x['video_id'] for x in rows if x['exercise_id'] == e])
                    for e, (p, v) in DECLARED_EXERCISES.items()],
                eligible_candidates=[x for x in rows if x['status'].startswith('LICENSED_CANDIDATE_')],
                rows=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=DATA)
    args = parser.parse_args()
    directory = args.directory
    output = directory / 'source-eligibility.json'
    if output.exists():
        raise FileExistsError('Preserve the source audit')
    manifest = json.loads((directory / 'metadata-receipts.json').read_bytes())
    for receipt in manifest['receipts']:
        path = ROOT / receipt['path']
        if hashlib.sha256(path.read_bytes()).hexdigest() != receipt['sha256']:
            raise ValueError('Publisher metadata changed')
    report = audit_catalogue(*[json.loads((directory / name).read_bytes()) for name in
        ['wger-videos.json', 'wger-licenses.json', 'wger-exerciseinfo.json']])
    report.update(schema='senpai-open-reference-source-audit/1',
        dvids_757660_rights=asset_rights((directory / 'dvids-757660.html').read_text(encoding='utf-8')),
        no_raw_video_downloaded=True, no_manual_labels=True, final_subjects_opened=False,
        camera_breadth_points_earned=0, independent_label_points_earned=0,
        human_rep_precision=None, production_changed=False,
        metadata_receipts_sha256=hashlib.sha256((directory / 'metadata-receipts.json').read_bytes()).hexdigest())
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k != 'rows'}, indent=2))


if __name__ == '__main__':
    main()
