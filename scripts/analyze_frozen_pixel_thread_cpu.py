"""Coarse procfs thread-counter attribution over measured scan/call brackets.

Thread names are observed labels, not inferred operator identities. Missing,
exited or recycled identities cannot be assigned zero CPU or silently repaired.
"""
import argparse,collections,hashlib,json
from pathlib import Path
from analyze_frozen_pixel_camera import analyze as analyze_capacity
from profile_live_processing_stages import stats


def parse_stat(raw,expected_tid):
    left,right=raw.find('('),raw.rfind(')')
    if left<1 or right<=left:raise ValueError('Malformed procfs comm field')
    tid=int(raw[:left].strip());fields=raw[right+1:].split()
    if tid!=int(expected_tid) or tid<=0 or len(fields)<20:raise ValueError('Missing or mismatched original thread identity')
    user,system,start=[int(fields[k]) for k in [11,12,19]]
    if user<0 or system<0 or start<=0:raise ValueError('Invalid CPU/start counters')
    return dict(tid=tid,name=raw[left+1:right],user_ticks=user,system_ticks=system,start_ticks=start)


def census(snapshot):
    if snapshot['scan_start_ns']<=0 or snapshot['scan_end_ns']<snapshot['scan_start_ns']:raise ValueError('Original scan bounds required')
    rows={};unreadable=[]
    for entry in snapshot['threads']:
        tid=int(entry['tid'])
        if tid in rows or tid in unreadable:raise ValueError('Duplicate thread census identity')
        if 'read_error' in entry:unreadable.append(tid);continue
        rows[tid]=parse_stat(entry['raw_stat'],tid)
    return rows,unreadable


def interval(before,after,hz,start,end):
    if type(hz) is not int or hz<=0:raise ValueError('Recorded sysconf clock tick frequency required')
    a,unread_a=census(before);b,unread_b=census(after)
    if not before['scan_end_ns']<=start<end<=after['scan_start_ns']:raise ValueError('Scans must bracket the original estimator call')
    matched=[];reused=[]
    for tid in sorted(set(a)&set(b)):
        first,last=a[tid],b[tid]
        if first['start_ticks']!=last['start_ticks']:reused.append(tid);continue
        user=last['user_ticks']-first['user_ticks'];system=last['system_ticks']-first['system_ticks']
        if user<0 or system<0:raise ValueError('Stable-identity CPU counter moved backwards')
        matched.append(dict(tid=tid,start_ticks=first['start_ticks'],name_before=first['name'],name_after=last['name'],user_ticks=user,system_ticks=system,cpu_ms=(user+system)*1000/hz))
    return dict(matched=matched,born=sorted(set(b)-set(a)-set(unread_a)),exited=sorted(set(a)-set(b)-set(unread_b)),reused=reused,unreadable_before=unread_a,unreadable_after=unread_b,enumeration_supported=before['enumeration_error'] is None and after['enumeration_error'] is None,scan_overhead_ns=before['scan_end_ns']-before['scan_start_ns']+after['scan_end_ns']-after['scan_start_ns'],bracket_ns=after['scan_end_ns']-before['scan_start_ns'])


def analyze(summary,blocks):
    if summary['schema']!='senpai-frozen-pixel-thread-cpu/1':raise ValueError('Explicit profiled research schema required')
    capacity=analyze_capacity(dict(summary,schema='senpai-frozen-pixel-camera/1'),blocks)
    hz=summary['clock_ticks_per_second'];results=[]
    for block in blocks:
        by_thread={};by_name=collections.Counter();changes=collections.Counter();overheads=[];brackets=[];observed_ticks=0
        intervals=[]
        for row in block['rows'][8:]:
            value=interval(row['thread_before'],row['thread_after'],hz,row['estimate_start_ns'],row['estimate_end_ns']);intervals.append(value)
            overheads.append(value['scan_overhead_ns']);brackets.append(value['bracket_ns'])
            for key in ['born','exited','reused','unreadable_before','unreadable_after']:changes[key]+=len(value[key])
            changes['unsupported_enumeration_intervals']+=int(not value['enumeration_supported'])
            for thread in value['matched']:
                key=(thread['tid'],thread['start_ticks']);name=thread['name_before'] if thread['name_before']==thread['name_after'] else thread['name_before']+' -> '+thread['name_after']
                total=thread['user_ticks']+thread['system_ticks'];by_name[name]+=total;observed_ticks+=total
                if key not in by_thread:by_thread[key]=dict(tid=thread['tid'],start_ticks=thread['start_ticks'],names=set(),user_ticks=0,system_ticks=0,matched_intervals=0)
                saved=by_thread[key];saved['names'].add(name);saved['user_ticks']+=thread['user_ticks'];saved['system_ticks']+=thread['system_ticks'];saved['matched_intervals']+=1
        threads=[]
        for value in by_thread.values():threads.append(dict(value,names=sorted(value['names']),cpu_ms=(value['user_ticks']+value['system_ticks'])*1000/hz,is_probe_owner=value['tid']==block['rows'][8]['owner_tid']))
        matched_count=sum(len(v['matched']) for v in intervals)
        results.append(dict(folder=block['metadata']['folder'],camera_active=block['metadata']['camera_active'],case_id=block['metadata']['case_id'],clock_ticks_per_second=hz,measured_intervals=24,matched_thread_intervals=matched_count,scan_overhead=stats(overheads),scan_to_scan_bracket=stats(brackets),observed_stable_identity_ticks=observed_ticks if matched_count else None,observed_stable_identity_cpu_ms=observed_ticks*1000/hz if matched_count else None,identity_or_read_changes=dict(changes),threads=sorted(threads,key=lambda x:(-x['cpu_ms'],x['tid'])),names=[dict(name=name,ticks=ticks,cpu_ms=ticks*1000/hz) for name,ticks in by_name.most_common()],intervals=intervals))
    return dict(schema='senpai-frozen-pixel-thread-cpu-analysis/1',kind='coarse-original-thread-counter-brackets-not-native-operator-CPU-proof',capacity_control=capacity,blocks=capacity['blocks'],thread_results=results,scan_overhead_included_in_thread_deltas=True,unobserved_or_recycled_thread_CPU=None,zero_ticks_proves_idle=False,thread_names_identify_native_operators=False,process_cpu_equals_native_cpu=False,capture_to_result_latency=None,live_delivered_fps=None,readiness_points_earned=0,human_rep_precision=None)


def main():
    p=argparse.ArgumentParser();p.add_argument('--directory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve original thread evidence')
    summary=json.loads((a.directory/'summary.json').read_bytes());blocks=[]
    for meta in summary['blocks']:
        folder=a.directory/meta['folder'];blocks.append(dict(metadata=meta,rows=json.loads((folder/'rows.json').read_bytes()),errors=json.loads((folder/'errors.json').read_bytes())))
    report=analyze(summary,blocks);report.update(api=summary['api'],device=summary['device'],compiler=summary['compiler'],model_sha256=summary['model_sha256'],input_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(a.directory.rglob('*')) if p.is_file()})
    a.output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps([dict(folder=x['folder'],observed_stable_identity_cpu_ms=x['observed_stable_identity_cpu_ms'],scan_overhead=x['scan_overhead'],identity_or_read_changes=x['identity_or_read_changes'],top_names=x['names'][:5]) for x in report['thread_results']],indent=2))


if __name__=='__main__':main()
