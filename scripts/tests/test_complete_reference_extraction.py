import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from extract_exercise_quality_features import SCHEMA,decoded_frames,process_video


class CompleteReferenceExtractionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'Research decoder requires FFmpeg')
    def test_uncapped_decoder_retains_the_original_prefix_and_decodes_the_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            video=Path(directory)/'source.mp4'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=32x32:rate=1','-t','26',
                            '-c:v','mpeg4',str(video)],check=True)
            limited=list(decoded_frames(video,1000));complete=list(decoded_frames(video,1000,None))
            self.assertEqual([t for t,_,_ in limited],list(range(0,24000,1000)))
            self.assertEqual([t for t,_,_ in complete],list(range(0,26000,1000)))
            for a,b in zip(limited,complete):
                self.assertEqual(a[0],b[0]);self.assertTrue((a[1]==b[1]).all())
            for limit in [0,-1,1.5,True]:
                with self.assertRaises(ValueError):list(decoded_frames(video,1000,limit))
                with self.assertRaises(ValueError):process_video(str(video),1000,maximum_duration_ms=limit)

    def test_duration_limit_is_part_of_cache_identity_including_explicit_unlimited(self):
        from mediapipe.tasks.python import vision
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'subject_001_squat_good_front.mp4';source.write_bytes(b'identity-only-test-source')
            model=root/'model.task';model.write_bytes(b'identity-only-test-model')
            model_sha=hashlib.sha256(model.read_bytes()).hexdigest()
            cache=root/'cache';cache.mkdir();target=cache/(source.stem+'.json.gz')
            payload={'schema':SCHEMA,'model_sha256':model_sha,'sample_period_ms':66,
                     'source_video_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'maximum_sampled_duration_ms':24000}
            def save():
                with gzip.open(target,'wt') as stream:json.dump(payload,stream)
            with patch.dict('extract_exercise_quality_features.POSE_MODELS',{'full':(model,model_sha)}):
                save()
                with patch.object(vision.PoseLandmarker,'create_from_options',side_effect=RuntimeError('cache-miss')) as inference:
                    self.assertEqual(process_video(str(source),66,str(cache)) ,(source.name,'cached'))
                    inference.assert_not_called()
                    with self.assertRaisesRegex(RuntimeError,'cache-miss'):process_video(str(source),66,str(cache),'full',None)
                payload['maximum_sampled_duration_ms']=None;save()
                with patch.object(vision.PoseLandmarker,'create_from_options',side_effect=RuntimeError('cache-miss')) as inference:
                    self.assertEqual(process_video(str(source),66,str(cache),'full',None),(source.name,'cached'))
                    inference.assert_not_called()
                    with self.assertRaisesRegex(RuntimeError,'cache-miss'):process_video(str(source),66,str(cache),'full',24000)
                del payload['maximum_sampled_duration_ms'];save()
                with patch.object(vision.PoseLandmarker,'create_from_options',side_effect=RuntimeError('cache-miss')):
                    with self.assertRaisesRegex(RuntimeError,'cache-miss'):process_video(str(source),66,str(cache),'full',None)


if __name__=='__main__':unittest.main()
