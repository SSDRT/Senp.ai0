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
    parser.add_argument('--cache-directory',type=Path,default=DATA/'rep-world-feature-cache')
    parser.add_argument('--reference-video',default='subject_005_shoulder_good_front')
    parser.add_argument('--source-video',default='subject_001_shoulder_good_front')
    parser.add_argument('--side',choices=['left','right'],default='left')
    parser.add_argument('--output',type=Path,default=ROOT/'validation-app/src/androidTest/resources/qualified-rep-native.json.gz')
    parser.add_argument('--provenance-output',type=Path,default=ROOT/'models/qualified_rep_fixture.provenance.json')
    parser.add_argument('--primary-cache-directory',type=Path)
    parser.add_argument('--reference-cache-directory',type=Path)
    parser.add_argument('--reference-curve-fit',choices=['ORDINARY_LEAST_SQUARES','MEDIAN_PAIRWISE','BOUNDED_MEDIAN_PAIRWISE'],default='ORDINARY_LEAST_SQUARES')
    args=parser.parse_args()
    names={'reference':args.reference_video,'source':args.source_video}
    if args.primary_cache_directory:names['primary_reference']=args.reference_video
    fixture={'kind':'native-numerical-parity-not-expert-rep-accuracy','dataset_doi':'10.17632/kgbb3yn47p.3','license':'CC BY 4.0','exercise_id':'overhead_press','side':args.side.upper(),'videos':{}}
    for role,name in names.items():
        directory=args.primary_cache_directory if role=='primary_reference' else args.reference_cache_directory if role=='reference' and args.reference_cache_directory else args.cache_directory
        with gzip.open(directory/(name+'.json.gz'),'rt') as stream:video=json.load(stream)
        fixture['videos'][role]={key:video[key] for key in ['video','source_video_sha256','model_sha256','sample_period_ms']}
        fixture['videos'][role]['frames']=[{key:f[key] for key in ['timestamp_ms','image_width','image_height','landmarks']} for f in video['frames']]
    report_path=args.replay_directory/(args.source_video+'-'+args.side+'.json')
    report=json.loads(report_path.read_text())
    if report['reference_video_sha256']!=fixture['videos']['reference']['source_video_sha256'] or report['video_sha256']!=fixture['videos']['source']['source_video_sha256']:
        raise ValueError('Replay and fixture videos differ')
    if fixture['videos']['reference']['model_sha256']!=fixture['videos']['source']['model_sha256']:
        raise ValueError('Reference and source models differ')
    if report['status']!='REPLAYED' or report['exercise']!=fixture['exercise_id'] or report['side']!=fixture['side']:
        raise ValueError('Replay policy and fixture differ')
    if 'primary_reference' in fixture['videos']:
        primary=fixture['videos']['primary_reference'];reference=fixture['videos']['reference']
        if primary['source_video_sha256']!=reference['source_video_sha256'] or [f['timestamp_ms'] for f in primary['frames']]!=[f['timestamp_ms'] for f in reference['frames']]:
            raise ValueError('Primary and fallback reference source identities or timestamps differ')
    fixture['expected_reference_cycles']=report['observed_reference_cycles']
    if args.reference_curve_fit!='ORDINARY_LEAST_SQUARES':fixture['expected_reference_curve_fit']=args.reference_curve_fit
    fixture['expected_counts']={key:value for key,value in report['counts'].items() if key!='legacy_raw'}
    fixture['expected_verdicts']=[event['verdict'] for event in report['attempts']]
    fixture['expected_attempts']=report['attempts']
    target=args.output;target.parent.mkdir(parents=True,exist_ok=True)
    buffer=io.BytesIO()
    with gzip.GzipFile(fileobj=buffer,mode='wb',mtime=0) as stream:stream.write(json.dumps(fixture,separators=(',',':'),allow_nan=False).encode())
    target.write_bytes(buffer.getvalue())
    provenance={key:fixture[key] for key in ['kind','dataset_doi','license']}
    provenance['source_videos']={role:{k:v for k,v in video.items() if k!='frames'} for role,video in fixture['videos'].items()}
    provenance['fixture_sha256']=hashlib.sha256(target.read_bytes()).hexdigest()
    provenance['expected_results_source_sha256']=hashlib.sha256(report_path.read_bytes()).hexdigest()
    provenance['reference_curve_fit']=args.reference_curve_fit
    provenance['observation_counts']={role:len(video['frames']) for role,video in fixture['videos'].items()}
    provenance['attribution']='Duddela Sai Prashanth et al. (2026), Mendeley Data V3, DOI 10.17632/kgbb3yn47p.3. See core-motion NOTICE-mendeley-quality.txt and CC BY 4.0 license.'
    provenance['changes']='Selected two clips; extracted pinned model MP33 image/world landmarks/confidences at original source PTS; optionally included the primary reference for backend fallback. Retained no video pixels. Expected engine results are parity targets, not human annotations.'
    args.provenance_output.write_text(json.dumps(provenance,indent=2)+'\n',newline='\n')
    print('Native fixture bytes',target.stat().st_size,'expected',fixture['expected_counts'])

if __name__=='__main__':main()
