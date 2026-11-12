"""Recheck sparse RGB activity seeds densely; preserve every original qualification gate.

The first scan remains a failed complete-bout proposal experiment. This separate
freeze adds original context around ALL coarse hits before testing the same
activity rules at 66 ms. No calibration/source verdict selects a window.
"""
import gzip
import json
import math

from extract_mmfit_reference import ROOT, DATA, SOURCE_SHA, PROTOCOL as RAW_PROTOCOL, extract, propose_bouts, write_cache, sha
from fetch_mmfit_reference_video import VIDEO, SIZE, MD5, file_hashes

PROTOCOL=ROOT/'models/mmfit-dense-proposal-protocol.json'
DIRECTORY=DATA/'dense-proposals'


def seed_windows(hits, duration_ms):
    if any(b<=a for a,b in zip(hits,hits[1:])) or any(t<0 or t>duration_ms for t in hits):
        raise ValueError('Seed timestamps must be original increasing in-range observations')
    windows=[]
    for timestamp in hits:
        start,end=max(0,timestamp-10000),min(math.floor(duration_ms),timestamp+10000)
        if windows and start<=windows[-1]['end_ms']:
            windows[-1]['end_ms']=end;windows[-1]['seed_observations']+=1
        else:windows.append(dict(start_ms=start,end_ms=end,seed_observations=1))
    return windows


def run():
    spec=json.loads(PROTOCOL.read_text())
    if (spec.get('schema')!='mmfit-dense-proposal/1' or spec.get('video_sha256')!=SOURCE_SHA or
        spec.get('seed_context_ms')!=10000 or spec.get('fine_period_ms')!=66 or
        spec.get('activity_gates_changed') is not False or spec.get('production_gates_changed') is not False or
        spec.get('selection')!='first-chronological-fine-eligible-bout' or
        spec.get('allow_final_test_access') is not False or spec.get('annotation_archive_access') is not False):
        raise ValueError('Frozen dense proposal scope changed')
    for receipt in spec['source_receipts']:
        normalized='sha256_lf' in receipt
        if sha(ROOT/receipt['path'],normalized)!=receipt.get('sha256_lf',receipt.get('sha256')):
            raise ValueError('Frozen research source changed: '+receipt['path'])
    source=DATA/VIDEO
    if source.stat().st_size!=SIZE or file_hashes(source)!=dict(md5=MD5,sha256=SOURCE_SHA):raise ValueError('Source changed')
    diagnostics=json.loads((DATA/'proposal-diagnostics.json').read_text())
    with gzip.open(DATA/'coarse.json.gz','rt') as stream:coarse=json.load(stream)
    windows=seed_windows(diagnostics['proposal_hit_pts_ms'],coarse['source_duration_ms'])
    if windows!=spec['windows']:raise ValueError('Frozen seed windows changed')
    if DIRECTORY.exists():raise FileExistsError('Preserve earlier dense research')
    DIRECTORY.mkdir()
    entries=[]
    # Each complete candidate window is inferred with a fresh graph, never joined.
    for index,window in enumerate(windows):
        payload=extract(source,66,window)
        payload['raw_extractor_protocol_sha256']=payload['protocol_sha256']
        payload['protocol_sha256']=sha(PROTOCOL)
        path=DIRECTORY/f'window-{index:02}.json.gz';write_cache(path,payload)
        bouts=propose_bouts(payload['frames'],payload['source_duration_ms'])
        # Context padding stays inside this observed original contiguous interval.
        for bout in bouts:
            bout['start_ms']=max(bout['start_ms'],window['start_ms'])
            bout['end_ms']=min(bout['end_ms'],window['end_ms'])
        entries.append(dict(window=window,cache_path=path.relative_to(ROOT).as_posix(),cache_sha256=sha(path),
            observations=len(payload['frames']),eligible_bouts=bouts))
    candidates=[dict(cache_path=e['cache_path'],cache_sha256=e['cache_sha256'],bout=b) for e in entries for b in e['eligible_bouts']]
    report=dict(schema='mmfit-dense-proposal-extraction/1',protocol_sha256=sha(PROTOCOL),windows=entries,
        selected=candidates[0] if candidates else None,activity_gates_changed=False,production_gates_changed=False,
        independent_form_labels=False,final_test_access=False,production_changed=False)
    (DIRECTORY/'extraction.json').write_text(json.dumps(report,indent=2)+'\n',newline='\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':run()
