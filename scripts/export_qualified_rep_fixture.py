"""Export a small CC BY 4.0 landmark-only native replay fixture; no source media."""
import gzip
import argparse
import hashlib
import io
import json
from pathlib import Path
from extract_exercise_quality_features import DATA,ROOT

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay-directory',type=Path,default=DATA/'qualified-video-replay')
    args=parser.parse_args()
    names={'reference':'subject_005_shoulder_good_front','source':'subject_001_shoulder_good_front'}
    fixture={'kind':'native-numerical-parity-not-expert-rep-accuracy','dataset_doi':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0','exercise_id':'overhead_press','videos':{}}
    for role,name in names.items():
        with gzip.open(DATA/'rep-world-feature-cache'/(name+'.json.gz'),'rt') as stream:video=json.load(stream)
        fixture['videos'][role]={key:video[key] for key in ['video','source_video_sha256','model_sha256','sample_period_ms']}
        fixture['videos'][role]['frames']=[{key:f[key] for key in ['timestamp_ms','image_width','image_height','landmarks']} for f in video['frames']]
    report=json.loads((args.replay_directory/'subject_001_shoulder_good_front-left.json').read_text())
    fixture['expected_counts']={key:value for key,value in report['counts'].items() if key!='legacy_raw'}
    fixture['expected_verdicts']=[event['verdict'] for event in report['attempts']]
    fixture['expected_attempts']=report['attempts']
    target=ROOT/'validation-app/src/androidTest/resources/qualified-rep-native.json.gz';target.parent.mkdir(parents=True,exist_ok=True)
    buffer=io.BytesIO()
    with gzip.GzipFile(fileobj=buffer,mode='wb',mtime=0) as stream:stream.write(json.dumps(fixture,separators=(',',':'),allow_nan=False).encode())
    target.write_bytes(buffer.getvalue())
    provenance={key:fixture[key] for key in ['kind','dataset_doi','license']}
    provenance['source_videos']={role:{k:v for k,v in video.items() if k!='frames'} for role,video in fixture['videos'].items()}
    provenance['fixture_sha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    provenance['expected_results_source_sha256']=hashlib.sha256((args.replay_directory/'subject_001_shoulder_good_front-left.json').read_bytes()).hexdigest()
    provenance['attribution']='Duddela Sai Prashanth et al. (2026), Mendeley Data V3, DOI 10.17632/kgbb3yn47p.3. See core-motion NOTICE-mendeley-quality.txt and CC BY 4.0 license.'
    provenance['changes']='Selected two clips; extracted pinned Full MP33 image/world landmarks/confidences at original source PTS; retained no video pixels. Expected engine results are parity targets, not human annotations.'
    (ROOT/'models/qualified_rep_fixture.provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',newline='\n')
    print('Native fixture bytes',target.stat().st_size,'expected',fixture['expected_counts'])

if __name__=='__main__':main()
