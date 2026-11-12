"""Export parity targets for the recovered LEFT policy on an unchanged raw landmark fixture."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
from extract_exercise_quality_features import DATA,ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay-directory',type=Path,default=DATA/'calibration-envelope-heavy-replay')
    args=parser.parse_args()
    fixture=json.loads(gzip.decompress((ROOT/'validation-app/src/androidTest/resources/qualified-rep-heavy-native.json.gz').read_bytes()))
    source=fixture['videos']['source'];reference=fixture['videos']['reference']
    path=args.replay_directory/(source['video'].removesuffix('.mp4')+'-left.json')
    report=json.loads(path.read_text())
    assert report['video_sha256']==source['source_video_sha256'] and report['reference_video_sha256']==reference['source_video_sha256']
    assert report['side']=='LEFT' and report['status']=='REPLAYED' and report['exercise']==fixture['exercise_id']
    envelope=report['driver_envelope']
    payload={'kind':'native-engine-parity-expectations-not-expert-labels','side':'LEFT',
        'reference_video_sha256':report['reference_video_sha256'],'source_video_sha256':report['video_sha256'],
        'expected_reference_cycles':report['observed_reference_cycles'],'expected_driver_envelope':envelope,
        'expected_counts':{k:v for k,v in report['counts'].items() if k!='legacy_raw'},
        'expected_verdicts':[e['verdict'] for e in report['attempts']],'expected_attempts':report['attempts']}
    target=ROOT/'validation-app/src/androidTest/resources/qualified-rep-envelope-expectations.json'
    target.write_text(json.dumps(payload,indent=2)+'\n',newline='\n')
    provenance={'kind':payload['kind'],
        'raw_observations':'Reuse unchanged qualified-rep-heavy-native.json.gz, with LEFT side. No landmark duplication or new human labels.',
        'source_video':source['video'],'source_sha256':source['source_video_sha256'],
        'reference_video':reference['video'],'reference_sha256':reference['source_video_sha256'],'model_sha256':reference['model_sha256'],
        'expectations_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'source_replay_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'dataset_doi':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0',
        'baseline_self_replay':f'{envelope["baseline_accepted_self_cycles"]}/{report["observed_reference_cycles"]}; withheld',
        'recovered_self_replay':f'{report["reference_self_replay"]["accepted"]}/{report["observed_reference_cycles"]}; unchanged >=80% gate',
        'driver_bounds':envelope}
    (ROOT/'models/reference_range_fixture.provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',newline='\n')
    print('Reused verified source',source['video'],'with',payload['expected_counts']['qualified'],'counted outputs; not human labels')


if __name__=='__main__':main()
