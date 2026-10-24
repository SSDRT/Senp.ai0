"""Version-five parity targets over unchanged licensed raw landmark fixtures."""
import gzip
import hashlib
import json
from extract_exercise_quality_features import DATA, ROOT


def main():
    directory=ROOT/'validation-app/src/androidTest/resources'
    cases={
        'full':('qualified-rep-native.json.gz','multipass-capped-full-standard','left'),
        'heavy':('qualified-rep-heavy-native.json.gz','multipass-capped-heavy-standard','right'),
        'range_recovery':('qualified-rep-heavy-native.json.gz','multipass-capped-heavy-standard','left'),
        'robust':('qualified-rep-robust-native.json.gz','multipass-complete-heavy-robust','left'),
    }
    payload={}
    provenance={}
    for name,(raw,replay,side) in cases.items():
        raw_path=directory/raw
        fixture=json.loads(gzip.decompress(raw_path.read_bytes()))
        reference=fixture['videos']['reference'];source=fixture['videos']['source']
        report_path=DATA/replay/(source['video'].removesuffix('.mp4')+'-'+side+'.json')
        report=json.loads(report_path.read_text())
        assert report['status']=='REPLAYED' and report['side']==side.upper() and report['exercise']==fixture['exercise_id']
        assert report['video_sha256']==source['source_video_sha256'] and report['reference_video_sha256']==reference['source_video_sha256']
        assert report['frame_count']==len(source['frames']) and report['reference_frame_count']==len(reference['frames'])
        payload[name]={'kind':'versioned-engine-parity-targets-not-human-labels','side':side.upper(),
            'reference_video_sha256':report['reference_video_sha256'],'source_video_sha256':report['video_sha256'],
            'expected_reference_cycles':report['observed_reference_cycles'],
            'expected_counts':{k:v for k,v in report['counts'].items() if k!='legacy_raw'},
            'expected_verdicts':[r['verdict'] for r in report['attempts']],'expected_attempts':report['attempts']}
        provenance[name]={'raw_fixture':raw,'raw_fixture_sha256':hashlib.sha256(raw_path.read_bytes()).hexdigest(),
            'replay_directory':replay,'replay_sha256':hashlib.sha256(report_path.read_bytes()).hexdigest(),
            'reference_video':reference['video'],'source_video':source['video'],
            'reference_video_sha256':reference['source_video_sha256'],'source_video_sha256':source['source_video_sha256'],
            'model_sha256':reference['model_sha256']}
    target=directory/'qualified-rep-v5-parity.json'
    target.write_text(json.dumps(payload,indent=2)+'\n',newline='\n')
    (ROOT/'models/v5_parity_fixture.provenance.json').write_text(json.dumps({
        'kind':'updated-engine-expectations-on-unchanged-raw-observations','reference_schema':'qualified-reference-reps/5',
        'dataset_doi':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0','new_human_labels':0,
        'expectations_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'cases':provenance,
        'note':'Original landmark fixtures and earlier policy expectations remain unchanged; these targets verify the new runtime policy.'},indent=2)+'\n',newline='\n')
    print({name:value['expected_counts'] for name,value in payload.items()})


if __name__=='__main__':main()
