"""Already-opened selection cohort only, after a frozen development pass."""
import json
from audit_ready_form_research import ROOT,DATA,sha,compare


def main():
    path=ROOT/'models/preparation-fault-validation-protocol.json'
    spec=json.loads(path.read_bytes())
    if (spec['schema']!='ready-form-validation/1' or spec['kind']!='armed-preparation-clear-fault/1' or
        spec['compiler']!='qualified-rep-compiler/8' or not spec['validation_access'] or spec['final_test_access'] or
        spec['subjects']!=['05','06','07'] or spec['reference_subject']!='01' or spec['thresholds_changed']):
        raise PermissionError('Frozen already-opened validation scope required')
    for r in spec['source_receipts']:
        if sha(ROOT/r['path'],'sha256_lf' in r)!=r.get('sha256_lf',r.get('sha256')):raise ValueError('Frozen source/input changed')
    if not json.loads((DATA/'preparation-fault-development-comparison.json').read_bytes())['promotion_gate_passed']:
        raise PermissionError('Failed development candidate cannot progress')
    before=json.loads((DATA/'uiprmd/clear-ready-validation-replay.json').read_bytes())
    candidate=DATA/'uiprmd/preparation-fault-validation-replay.json';after=json.loads(candidate.read_bytes())
    if before['compiler']!='qualified-rep-compiler/7' or after['compiler']!=spec['compiler'] or after['candidate_protocol_sha256']!=sha(path):
        raise ValueError('Wrong candidate/baseline')
    result=compare(before,after,'validation')
    result['promotion_gate_passed']=result.pop('development_promotion_gate_passed')
    result.update(schema='preparation-fault-validation-comparison/1',kind='already-opened-skeletal-selection-not-final-or-camera-accuracy',
        protocol_sha256=sha(path),candidate_sha256=sha(candidate),final_test_access=False,human_live_rep_precision=None,readiness_points_earned=0)
    output=DATA/'preparation-fault-validation-comparison.json'
    if output.exists():raise FileExistsError('Preserve comparison')
    output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(result,indent=2))
    if not result['promotion_gate_passed']:raise SystemExit('Candidate rejected by validation selection gate')


if __name__=='__main__':main()
