"""Separately frozen check on already-opened validation; no final-test read or camera accuracy claim."""
import json
from audit_ready_form_research import ROOT,DATA,sha,compare


def main():
    protocol_path=ROOT/'models/ready-form-validation-protocol.json'
    spec=json.loads(protocol_path.read_text())
    if (spec.get('schema')!='ready-form-validation/1' or spec.get('subjects')!=['05','06','07'] or
        spec.get('validation_access') is not True or spec.get('final_test_access') is not False or
        spec.get('compiler')!='qualified-rep-compiler/6'):
        raise ValueError('Validation-only frozen scope changed')
    for receipt in spec['source_receipts']:
        normalized='sha256_lf' in receipt
        if sha(ROOT/receipt['path'],normalized)!=receipt.get('sha256_lf',receipt.get('sha256')):
            raise ValueError('Frozen input/source changed: '+receipt['path'])
    baseline=DATA/'uiprmd/side-binding-validation-fixed-replay.json'
    candidate=DATA/'uiprmd/ready-form-validation-replay.json'
    before,after=[json.loads(path.read_text()) for path in [baseline,candidate]]
    if (before['mapping_mode']!='fixed' or before['compiler']!='qualified-rep-compiler/5' or
        after['compiler']!='qualified-rep-compiler/6' or after['candidate_protocol_sha256']!=sha(protocol_path)):
        raise ValueError('Wrong fixed-side baseline/candidate')
    result=compare(before,after,'validation')
    result['validation_promotion_gate_passed']=result.pop('development_promotion_gate_passed')
    result.update(schema='compatible-ready-form-validation/1',kind='already-opened-skeletal-validation-not-camera-precision',
        protocol_sha256=sha(protocol_path),baseline_sha256=sha(baseline),candidate_sha256=sha(candidate),
        subjects=['05','06','07'],test_subjects_opened=False,readiness_points_earned=0,human_live_rep_precision=None)
    output=DATA/'ready-form-validation-comparison.json'
    if output.exists():raise FileExistsError('Preserve validation evidence')
    output.write_text(json.dumps(result,indent=2)+'\n',newline='\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
