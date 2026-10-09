#!/usr/bin/env python3
"""Describe all current fixture attempts and compare matching per-JVM statistics.

No Gradle, Docker, JVM or measurement process is started by this tool.
"""
import argparse
import collections
import hashlib
import json
import math
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORKLOADS = ('analysis', 'write-wait', 'execution', 'repair', 'native', 'payload', 'capture-release')
GROUP_KEYS = ('corpus', 'mode', 'scenario', 'writerMode', 'documentVariant', 'implementation', 'language', 'units', 'phase')
ABSOLUTE_CLOCKS = {'requestedNs', 'enteredNs', 'exitedNs', 'sampleStartNano', 'writeQueuedAtNs', 'writeRequestedAtNs', 'writeAcquiredAtNs'}
NON_METRICS = {'iteration', 'trial', 'trials', 'requestId', 'attemptId', 'sampleId', 'measurementStartThreadId', 'measurementReturnThreadId'}
CAVEATS = [
    'No samples are pooled across JVMs: median and nearest-rank p95 are computed within each JVM, then their per-JVM summaries are described.',
    'Each paired change compares the same numbered fresh JVM pair. Three pairs have limited power; no equivalence, significance, no-regression or timing pass is inferred.',
    'All failed/interrupted/unmatched attempts and raw artifact paths remain in the attempt inventory; only the latest completed and validated attempt for each key enters comparisons.',
    'Recorded stable power/JVM settings and instantaneous idle-process CPU observations do not prove continuous host inactivity.',
    'Direct allocation traces cover specified coroutine/EDT segments and bookkeeping, not all platform or dispatcher allocation. Incomplete traces are counted and excluded only from comparable allocation metrics.',
    'Write wait is actual EDT write request-to-acquisition; queue delay, event-pump observation and read-body overlap are separate metrics. Read-body durations exclude read lock acquisition/release overhead.',
    'Native inspection includes indivisible preparation and callbacks; traversal budgets are cooperative. Native measurements have no allocation metric.',
    'Repair tab/space variants and frozen-reference/production implementations remain separate. Warmup rows are preserved by counts and raw hashes but excluded from measured statistics.',
    'Payload capacities, whole-JVM heap diagnostics and weak-reference release observations have distinct scopes. A release timeout or surviving final XML context is retained, not treated as successful release.',
    'Absolute clocks are retained only in original raw artifacts and are never compared as durations. Cancellation unwind metrics conditioned on successful cancellation do not describe every attempt.',
]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stats(values):
    values = [v for v in values if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)]
    if not values:
        return {'count': 0, 'median': None, 'p95': None, 'min': None, 'max': None}
    ordered = sorted(values)
    return {'count': len(values), 'median': statistics.median(values), 'p95': ordered[math.ceil(.95*len(values))-1], 'min': ordered[0], 'max': ordered[-1]}


def delta(before, after):
    return {'baseline': before, 'candidate': after,
            'absoluteDelta': None if before is None or after is None else after-before,
            'percentDelta': None if before in (None, 0) or after is None else 100*(after/before-1)}


def group_key(row):
    return json.dumps({k: row[k] for k in ('kind', *GROUP_KEYS) if k in row}, sort_keys=True)


def environment_signature(env):
    return {k:v for k,v in env.items() if k not in ('kind', 'runId', 'revision', 'jvmArguments') and not k.startswith('clockCalibration')}


def jvm_signature(attempt):
    vm=attempt['verifiedJVM']
    if vm.get('normalizedJvmArgs') is None:
        raise ValueError('Actual normalized JVM arguments missing')
    return {k:vm[k] for k in ('javaLauncherExecutable', 'javaVersion', 'javaVendor', 'minHeapSize', 'maxHeapSize', 'normalizedJvmArgs', 'actualHeapAndVmFlags')}


def summarize(attempt, workload):
    path=Path(attempt['raw'])
    if sha(path)!=attempt['rawEvidence']['sha256']:
        raise ValueError('Raw SHA256 differs from recorded evidence')
    vm=attempt['verifiedJVM']
    if sha(attempt['log'])!=vm['logSha256']:
        raise ValueError('Actual JVM launch log SHA256 differs')
    before=attempt['machineBefore'];after=attempt['machineAfter']
    if before['powerSignature']!=after['powerSignature'] or attempt.get('externalWorkAfter'):
        raise ValueError('Power settings changed or concurrent workload recorded')
    rows=[json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    envs=[r for r in rows if r.get('kind')=='environment']
    if len(envs)!=1 or not envs[0].get('runId'):
        raise ValueError('Expected one fresh-JVM environment runId')
    env=envs[0]
    if any(r.get('runId')!=env['runId'] for r in rows):
        raise ValueError('Missing or mixed raw runId')
    if env.get('revision') is not None and env['revision']!=attempt['sourceRevision']['head']:
        raise ValueError('Fixture revision differs from source revision')
    identities={};grouped=collections.defaultdict(list)
    for row in rows:
        if 'sha256Utf8' in row:
            identity_key=json.dumps({k:row[k] for k in GROUP_KEYS if k in row and k!='phase'},sort_keys=True)
            identity={k:row[k] for k in ('sha256Utf8','tabbedSha256Utf8','characters','initialCharacters','filename') if k in row}
            if identity_key in identities and identities[identity_key]!=identity:
                raise ValueError('Corpus identity conflict')
            identities[identity_key]=identity
        if row.get('kind') not in ('environment','corpus','warmup-threads'):
            grouped[group_key(row)].append(row)
    invariants=[];groups={}
    sample_kinds={'sample','write-wait-sample','cancellation','payload','release'}
    for key,records in grouped.items():
        first=records[0];warmup=first.get('phase')=='warmup'
        expected=None
        if first['kind'] in sample_kinds:
            if workload in ('payload','capture-release'):
                expected=1
            elif workload=='repair':
                expected=env['warmups']//2 if warmup else env['repeats']//2
            elif first['kind']=='cancellation':
                expected=env['cancellationTrials']
            else:
                expected=env['repeats']
        if expected is not None:
            complete=len(records)==expected
            index='trial' if first['kind']=='cancellation' else 'iteration'
            if index in first:
                expected_indices = list(range(expected))
                if workload=='repair':
                    expected_indices = list(range(0 if first['documentVariant']=='tab' else 1, expected*2, 2))
                complete=complete and sorted(r[index] for r in records)==expected_indices
            invariants.append({'group':json.loads(key),'expectedRows':expected,'actualRows':len(records),'complete':complete})
        metric_keys=sorted({k for r in records for k,v in r.items() if isinstance(v,(int,float)) and not isinstance(v,bool)
                            and k not in ABSOLUTE_CLOCKS|NON_METRICS and not k.endswith('AtNs') and not k.endswith('StartedNs')})
        measured=[] if warmup else records
        metrics={}
        for metric in metric_keys:
            selected=measured
            if metric=='directCoroutineAllocatedBytes':
                selected=[r for r in measured if r.get('allocationTraceComplete') is True]
            if metric=='workerDirectCoroutineAllocatedBytes':
                selected=[r for r in measured if r.get('workerAllocationSegmentsClosed') is True]
            metrics[metric]=stats([r.get(metric) for r in selected])
        bool_keys=sorted({k for r in records for k,v in r.items() if isinstance(v,bool)})
        booleans={k:{'true':sum(r.get(k) is True for r in records),'false':sum(r.get(k) is False for r in records),
                     'missingOrNull':sum(r.get(k) is None for r in records)} for k in bool_keys}
        outcome_keys=('outcome','status','result','thrownType','resolutionFailure','writerFailure','failureClass','failure')
        outcomes={k:dict(collections.Counter(str(r.get(k)) for r in records)) for k in outcome_keys if any(k in r for r in records)}
        groups[key]={'rows':len(records),'warmupExcluded':warmup,'metrics':metrics,'booleans':booleans,'outcomes':outcomes}
        if first['kind']=='cancellation':
            successes=[r for r in records if r.get('canceled') is True]
            groups[key]['successfulCancellationMetrics']={m:stats([r.get(m) for r in successes]) for m in ('wallNs','requestFromStartNs','unwindLatencyNs','checkLatencyNs')}
            groups[key]['requestedDelaysNs']=sorted({r['requestedDelayNs'] for r in records})
    measured_groups = [json.loads(k) for k in groups if json.loads(k)['kind'] in ('sample','write-wait-sample','payload') and json.loads(k).get('phase')!='warmup']
    expected_group_count = {'analysis':5,'write-wait':5,'execution':2,'repair':24,'native':12,'payload':5,'capture-release':6}[workload]
    invariants.append({'check':'expected measured scenario inventory','expectedGroups':expected_group_count,
                       'actualGroups':len(measured_groups),'complete':len(measured_groups)==expected_group_count})
    if workload=='analysis':
        cancellation_groups = [k for k in groups if json.loads(k)['kind']=='cancellation']
        invariants.append({'check':'all five cancellation corpora present','expectedGroups':5,
                           'actualGroups':len(cancellation_groups),'complete':len(cancellation_groups)==5})
    if workload=='repair':
        warmup_groups = [k for k in groups if json.loads(k).get('phase')=='warmup']
        invariants.append({'check':'all repair warmup variant/implementation scenarios present','expectedGroups':24,
                           'actualGroups':len(warmup_groups),'complete':len(warmup_groups)==24})
    violated_flags = collections.Counter()
    for row in rows:
        for flag in ('sampleCompleted','matchesExpectedGeometry','writeCompletedBeforeTiming','immediateHideInvariant'):
            if row.get(flag) is False:
                violated_flags[flag] += 1
        for flag in ('writerFailure','resolutionFailure'):
            if row.get(flag) is not None:
                violated_flags[flag] += 1
    # Verify each reported sample body count against its emitted raw body rows.
    body_mismatches=[]
    if workload in ('native','write-wait'):
        body_counts=collections.Counter((r.get('sampleId') if workload=='native' else (r.get('corpus'),r.get('iteration')))
                                        for r in rows if r.get('kind')=='read-body')
        for row in rows:
            if row.get('kind') in ('sample','write-wait-sample'):
                identifier=row.get('sampleId') if workload=='native' else (row.get('corpus'),row.get('iteration'))
                if row.get('readBodyCount')!=body_counts[identifier]:
                    body_mismatches.append({'sample':identifier,'reported':row.get('readBodyCount'),'observed':body_counts[identifier]})
    return {'raw':str(path.resolve()),'sha256':sha(path),'runId':env['runId'],'revision':attempt['sourceRevision']['head'],
            'sourceFingerprint':attempt['sourceFingerprint'],'environment':env,'environmentSignature':environment_signature(env),
            'jvmSignature':jvm_signature(attempt),'powerSignature':before['powerSignature'],
            'machineBefore':before,'machineAfter':after,'corpusIdentities':identities,
            'rowCounts':dict(collections.Counter(r.get('kind') for r in rows)),'groups':groups,
            'invariantCompleteness':{'checks':invariants,'allExpectedGroupsComplete':all(i['complete'] for i in invariants),
                                     'readBodyCountMismatches':body_mismatches,'observedBehaviorViolations':dict(violated_flags)},
            'corpusObservations':[r for r in rows if r.get('kind')=='corpus'],
            'releaseObservations':[r for r in rows if r.get('kind') in ('release','released','retention') or 'nonClearedContexts' in r]}


def compare_pair(before,after):
    for field in ('environmentSignature','jvmSignature','powerSignature','corpusIdentities'):
        if before[field]!=after[field]:
            raise ValueError('Matched pair differs: '+field)
    if set(before['groups'])!=set(after['groups']):
        raise ValueError('Matched pair group inventory differs')
    comparisons={}
    for key,b in before['groups'].items():
        c=after['groups'][key]
        if b['warmupExcluded']:
            continue
        if set(b['metrics'])!=set(c['metrics']):
            raise ValueError('Matched pair metric inventory differs')
        metrics={m:{stat:delta(b['metrics'][m][stat],c['metrics'][m][stat]) for stat in ('median','p95')} for m in b['metrics']}
        if 'successfulCancellationMetrics' in b:
            metrics['successfulCancellation']={m:{stat:delta(b['successfulCancellationMetrics'][m][stat],c['successfulCancellationMetrics'][m][stat])
                                                   for stat in ('median','p95')} for m in b['successfulCancellationMetrics']}
            if b['requestedDelaysNs']!=c['requestedDelaysNs']:
                raise ValueError('Cancellation delay schedule differs')
        comparisons[key]=metrics
    return comparisons


def report(state):
    result={'method':'Within-JVM median/nearest-rank p95 followed by numbered paired comparison; no pooling.',
            'caveats':CAVEATS,'provenance':{k:state.get(k) for k in ('candidateRequiredHead','sourceRevisions','sourceFingerprints','runnerSha256','initScriptSha256','planSha256')},
            'attemptInventory':state.get('commands',{}),'workloads':{},'unexpectedKeys':[]}
    expected_keys={f'{w}-{side}-{i}' for w in WORKLOADS for side in ('baseline','candidate') for i in range(1,4)}
    result['unexpectedKeys']=sorted(set(state.get('commands',{}))-expected_keys)
    seen_runs=set();seen_paths=set()
    for workload in WORKLOADS:
        item={'runs':{'baseline':{},'candidate':{}},'unmatchedOrInvalid':[],'pairedComparisons':{},'acrossJvm':{}}
        for side in ('baseline','candidate'):
            for i in range(1,4):
                key=f'{workload}-{side}-{i}';attempts=state.get('commands',{}).get(key,[])
                if not attempts or attempts[-1].get('status')!='completed':
                    item['unmatchedOrInvalid'].append({'key':key,'reason':'missing or latest attempt not completed'})
                    continue
                try:
                    run=summarize(attempts[-1],workload)
                    if run['runId'] in seen_runs or run['raw'] in seen_paths:
                        raise ValueError('Duplicate fresh runId or raw artifact path')
                    seen_runs.add(run['runId']);seen_paths.add(run['raw'])
                    item['runs'][side][str(i)]=run
                except (ValueError,KeyError,OSError,json.JSONDecodeError) as error:
                    item['unmatchedOrInvalid'].append({'key':key,'reason':str(error)})
        for i in range(1,4):
            b=item['runs']['baseline'].get(str(i));c=item['runs']['candidate'].get(str(i))
            if b is None or c is None:
                continue
            try:
                item['pairedComparisons'][str(i)]=compare_pair(b,c)
            except ValueError as error:
                item['unmatchedOrInvalid'].append({'pair':i,'reason':str(error)})
        pairs=item['pairedComparisons']
        if pairs:
            shared_groups=set.intersection(*(set(v) for v in pairs.values()))
            for group in sorted(shared_groups):
                shared_metrics=set.intersection(*(set(v[group]) for v in pairs.values()))-{'successfulCancellation'}
                item['acrossJvm'][group]={}
                for metric in sorted(shared_metrics):
                    item['acrossJvm'][group][metric]={}
                    for statistic in ('median','p95'):
                        paired=[{'pair':int(i),**values[group][metric][statistic]} for i,values in pairs.items()]
                        bm=stats([r['baseline'] for r in paired]);cm=stats([r['candidate'] for r in paired])
                        item['acrossJvm'][group][metric][statistic]={
                            'paired':paired,'baselinePerJvmStatisticSummary':bm,'candidatePerJvmStatisticSummary':cm,
                            'differenceOfJvmStatisticMedians':delta(bm['median'],cm['median']),
                            'pairedPercentChangeSummary':stats([r['percentDelta'] for r in paired]),
                            'candidateLargerPairs':sum(r['absoluteDelta'] is not None and r['absoluteDelta']>0 for r in paired)}
        item['completeThreePairs']=len(pairs)==3 and not item['unmatchedOrInvalid']
        result['workloads'][workload]=item
    result['all42CommandsMatched']=all(w['completeThreePairs'] for w in result['workloads'].values()) and not result['unexpectedKeys']
    result['allInvariantGroupsComplete']=all(run['invariantCompleteness']['allExpectedGroupsComplete'] and not run['invariantCompleteness']['readBodyCountMismatches'] and not run['invariantCompleteness']['observedBehaviorViolations']
                                            for w in result['workloads'].values() for side in w['runs'].values() for run in side.values()) if seen_runs else False
    result['status']='complete_descriptive_comparison' if result['all42CommandsMatched'] and result['allInvariantGroupsComplete'] else 'incomplete_or_invalid_evidence_retained'
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state',type=Path,default=ROOT/'performance-run-state.json')
    parser.add_argument('--output',type=Path)
    parser.add_argument('--self-test',action='store_true')
    args=parser.parse_args()
    if args.self_test:
        assert stats([1,2,3,4,5,6,7,8,9,10])['p95']==10
        assert stats([1,100])['median']==50.5
        assert delta(0,1)['percentDelta'] is None
        empty=report({'commands':{}})
        assert empty['status']=='incomplete_or_invalid_evidence_retained' and len(empty['workloads'])==7
        assert group_key({'kind':'sample','writerMode':'none'})!=group_key({'kind':'sample','writerMode':'unstaged'})
        assert group_key({'kind':'sample','documentVariant':'tab'})!=group_key({'kind':'sample','documentVariant':'space'})
        print('Comparator statistic/group/incomplete-state self checks passed; no workloads executed.')
        return
    rendered=json.dumps(report(json.loads(args.state.read_text())),indent=2,allow_nan=False)+'\n'
    if args.output:
        args.output.write_text(rendered)
    else:
        print(rendered,end='')


if __name__=='__main__':
    main()
