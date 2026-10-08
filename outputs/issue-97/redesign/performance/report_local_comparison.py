#!/usr/bin/env python3
"""Read completed local campaigns only. Produces evidence summaries, never a pass verdict."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET

ORDER = ['baseline-candidate', 'candidate-baseline'] * 3
JOBS = ['analyzeCold', 'analyzeReuse', 'visibleQuery', 'repair', 'cancelledAttempt']
WORKLOADS = ['analysis', 'write-wait', 'execution', 'repair', 'native', 'payload', 'capture-release', 'edit-restoration']
CORPORA = ['Ordinary.java', 'Nested.java', 'Closers.java', 'Whitespace.java', 'Nested.xml']
REPAIRS = ['ordinary-small-body', 'exact-256-line-budget', '257-line-refusal', 'exact-32768-character-budget', '32769-character-refusal', 'same-line-special-case']
NATIVE = [(name + ':' + mode, writer) for name in ['large-java', 'large-java-lazy', 'large-xml']
          for mode in (['direct'] if name == 'large-xml' else ['direct', 'scope'])
          for writer in (['none', 'late-traversal', 'late-lazy-lexer'] if name.endswith('lazy') else ['none', 'late-traversal'])]
CAPTURES = [f'Closers.{extension}-{units}' for extension in ['java', 'xml'] for units in [2000, 20000, 100000]]


def require(ok, message):
    if not ok:
        raise ValueError(message)


def load(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def get(row, path):
    for key in path.split('.'):
        if not isinstance(row, dict) or key not in row:
            return None
        row = row[key]
    return row


def stats(values):
    present = [value for value in values if value is not None]
    require(all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and v >= 0 for v in present), 'Invalid numeric observation')
    ordered = sorted(present)
    return {'trials': len(values), 'observed': len(present), 'nullOrUnsupported': len(values) - len(present),
            'median': statistics.median(present) if present else None,
            'observedSubsetP95': ordered[math.ceil(.95 * len(ordered)) - 1] if ordered else None,
            'p95Rank': math.ceil(.95 * len(ordered)) if ordered else None,
            'populationP95ClaimAllowed': bool(present) and len(present) == len(values), 'values': values}


def expected_cells(job):
    if job == 'native':
        return set(NATIVE)
    names = {'analysis': CORPORA, 'write-wait': CORPORA, 'payload': CORPORA, 'repair': REPAIRS,
             'edit-restoration': REPAIRS, 'execution': ['all', 'tokens'], 'capture-release': CAPTURES}[job]
    return {(name, '') for name in names}


def cell(row):
    return (row.get('corpus', row.get('mode', '')), row.get('writerMode', get(row, 'metadata.writerMode')) or '')


# Explicit actual metric paths. Allocation scopes remain separate, never summed.
METRICS = {
    'sample': ['wallNs', 'allocation.coroutineAllocatedBytes'],
    'cancellation': ['cancelToJoinNs', 'cancelToObservedReadUnwindNs', 'allocation.coroutineAllocatedBytes'],
    'write-wait': ['waitNs', 'queueDelayNs'],
    'execution': ['requestToObservedMarkupNs', 'synchronousRequestEntry.wallNs', 'synchronousRequestEntry.edtAllocatedBytes',
                  'unchangedPresentationCallback.wallNs', 'unchangedPresentationCallback.edtAllocatedBytes', 'allocation.coroutineAllocatedBytes'],
    'execution-lifecycle': [f'{phase}.{metric}' for phase in ['construction', 'request', 'close'] for metric in ['wallNs', 'edtAllocatedBytes']],
    'edit-restoration': ['mutationAndCommitNs', 'mutationAndCommitEdtAllocatedBytes', 'hideToObservedCorrectGuideNs',
                         'asyncAllocatedBytes', 'maximumObservedPollGapNs'],
    'payload': ['payload.reachablePrimitiveArrayPayloadBytes', 'payload.objectArrayReferenceSlots'],
    'payload-release': ['observation.gcObservationWallNs', 'observation.nonClearedArrays', 'observation.nonClearedBatches', 'observation.nonClearedCapturedStrings'],
    'capture-release': ['payload.sumCapturedBatchPrimitiveArrayPayloadBytes', 'payload.sumCapturedBatchObjectArrayReferenceSlots',
                        'payload.maximumBatchPrimitiveArrayPayloadBytes', 'captureAndDiagnosticInspectionNs',
                        'observation.gcObservationWallNs', 'observation.nonClearedArrays', 'observation.nonClearedBatches', 'observation.nonClearedCapturedStrings'],
}
FLAGS = ['completedBeforeRequest', 'firstReadRequestObserved', 'firstBodyObserved', 'analysisIncompleteAtQueue',
         'analysisIncompleteAtActualWriteRequest', 'writerRequestedInsideBody', 'outcome', 'expectedHide',
         'previousMarkValidAfterListeners', 'ownedWorkersQuiescent', 'observation.released', 'observation.timedOut',
         'writer.triggerObserved', 'writer.writeRequestedInsideTriggeredPhase', 'writer.writeRequestedInsideObservedRead',
         'writer.resolutionCompletedBeforeWriteRequest', 'refused']


def sdk(run, frozen):
    rows = []
    with Path(run['raw']).open() as stream:
        for n, line in enumerate(stream, 1):
            row = json.loads(line)
            require(row['sequence'] == n and (not rows or row['runId'] == rows[0]['runId']), 'Mixed SDK stream')
            rows.append(row)
    env = rows[0]
    require(env['kind'] == 'environment' and rows[-1]['kind'] == 'completed', 'Incomplete SDK stream')
    require(env['workload'] == rows[-1]['workload'] == run['job'], 'SDK workload mismatch')
    require((env['warmups'], env['repeats'], env['cancelTrials']) == (100, 30, 30), 'Formal counts changed')
    require(env['sourceRevision'] == frozen[run['side'] + 'Revision'], 'Revision mismatch')
    require(env['harnessClassSha256'] == frozen['harnessEquivalence']['classSha256'][run['side']], 'Loaded side harness changed')
    suite = ET.parse(Path(run['directory']) / 'sdk-test.xml').getroot()
    require([suite.get(k) for k in ['tests', 'failures', 'errors', 'skipped']] == ['1', '0', '0', '0'], 'SDK fixture did not pass')
    for artifact in run['artifactSnapshots']:
        require(sha(Path(run['directory']) / artifact['copy']) == artifact['sha256'], 'Copied SDK evidence changed')
    require(not [task for task in run['producerTaskOutcomes'] if task['outcome'] not in ['UP-TO-DATE', 'SKIPPED', 'NO-SOURCE']], 'Non-frozen SDK producer')
    kinds = {'analysis': ['sample', 'result', 'cancellation'], 'write-wait': ['write-wait'],
             'execution': ['execution', 'execution-lifecycle'], 'repair': ['sample', 'result'],
             'native': ['sample', 'result', 'native-control-coverage'], 'payload': ['sample', 'payload', 'payload-release'],
             'capture-release': ['capture-release'], 'edit-restoration': ['edit-restoration']}[run['job']]
    summaries = {}
    for kind in kinds:
        grouped = defaultdict(list)
        for row in rows:
            if row['kind'] == kind:
                grouped[cell(row)].append(row)
        require(set(grouped) == expected_cells(run['job']), f'Missing/extra SDK cells: {run["job"]}/{kind}')
        count = 1 if kind == 'native-control-coverage' or run['job'] in ['payload', 'capture-release'] else 30
        for key, items in sorted(grouped.items()):
            require(len(items) == count, f'Wrong trial count: {run["job"]}/{kind}/{key}')
            index_key = 'trial' if kind == 'cancellation' else 'resourceReplicate' if kind in ['payload', 'payload-release', 'capture-release'] else 'iteration'
            if kind != 'native-control-coverage':
                require(sorted(row[index_key] for row in items) == list(range(count)), f'Duplicate/missing trial index: {key}/{kind}')
            else:
                require(items[0]['attemptedSamples'] == 30 and (key[1] == 'none' or items[0]['samplesWithActualWriterInsideTriggeredPhase'] > 0), 'Native overlap coverage missing')
            if kind in ['payload-release', 'capture-release']:
                require(all(row['resourceReplicates'] == 1 and row['resourceReplicate'] == 0 for row in items), 'Resource replica contract changed')
            name = '|'.join([kind, *key])
            metrics = {path: stats([get(row, path) for row in items]) for path in METRICS.get(kind, [])}
            if kind == 'result' and run['job'] == 'native':
                metrics.update({path: stats([get(row, path) for row in items]) for path in
                                ['writer.externalDispatchGapNs', 'writer.edtQueueDelayNs', 'writer.writeWaitNs']})
            # Body-level counts and per-operation max/sum are distinct. Empty means unobserved, not zero.
            if any('reads' in row for row in items):
                for metric in ['maximumObservedReadHoldNs', 'sumObservedReadHoldNs', 'maximumObservedRequestToEnterNs']:
                    values = []
                    for row in items:
                        bodies = row.get('reads', [])
                        require(all(body['exitedNanos'] >= body['enteredNanos'] > 0 for body in bodies), 'Open/invalid read observation')
                        holds = [body['exitedNanos'] - body['enteredNanos'] for body in bodies]
                        queues = [body['enteredNanos'] - body['requestedNanos'] for body in bodies]
                        values.append((sum(holds) if metric.startswith('sum') else max(holds) if 'Hold' in metric else max(queues)) if bodies else None)
                    metrics[metric] = stats(values)
            phases = defaultdict(list)
            for row in items:
                for body in row.get('reads', []):
                    phases[body['phase']].append(body)
            phase_evidence = {phase: {
                'bodyObservations': len(bodies),
                'holdNs': stats([body['exitedNanos'] - body['enteredNanos'] for body in bodies]),
                'requestToEnterNs': stats([body['enteredNanos'] - body['requestedNanos'] for body in bodies]),
                'allocatedBytes': stats([body.get('allocatedBytes') for body in bodies]),
                'note': 'Body-level diagnostic distribution, not independent JVM samples; phases are not assumed equivalent across architectures.',
            } for phase, bodies in phases.items()}
            summaries[name] = {'count': count, 'metrics': metrics, 'readPhaseEvidence': phase_evidence,
                               'observations': {path: dict(Counter(str(get(row, path)) for row in items)) for path in FLAGS if any(get(row, path) is not None for row in items)},
                               'censoredRestorations': sum(row.get('expectedHide') is True and row.get('hideToObservedCorrectGuideNs') is None for row in items),
                               'scopeEvidence': [{k: v for k, v in row.items() if k in ['scope', 'readObservationCoverage', 'allocationScopeRule', 'restorationOrigin', 'nativePaintVerified', 'payload', 'observation', 'shape', 'metadata', 'writer']} for row in items],
                               'rawRowSequences': [row['sequence'] for row in items]}
    return summaries, {'environment': env, 'corpora': [{k: v for k, v in row.items() if k not in ['sequence', 'runId']} for row in rows if row['kind'] == 'corpus']}


def pure(run, _frozen):
    # Existing independent metrics.jq/Gradle BMF conversion remains authoritative; consume its completed output.
    bmf = load(Path(run['directory']) / 'bmf.json')
    require(len(bmf) == 8, 'Existing BMF conversion missing eight cases')
    rows = load(run['raw'])
    require(len(rows) == 8, 'JMH case count changed')
    expected = {(d, str(n)) for d in ['malformed', 'nested', 'siblings', 'sparse'] for n in [64, 4096]}
    require({(row['params']['distribution'], row['params']['pairCount']) for row in rows} == expected, 'JMH case identity changed')
    result = {}
    for row in rows:
        require(row['benchmark'] == 'com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark.' + run['job'], 'Wrong JMH method')
        key = row['params']['distribution'] + '|' + row['params']['pairCount']
        metrics = {}
        for name, raw_name, unit in [('latency', 'primaryMetric', 'ns/op'), ('allocation_bop', 'gc.alloc.rate.norm', 'B/op')]:
            metric = row['primaryMetric'] if raw_name == 'primaryMetric' else row['secondaryMetrics'][raw_name]
            require(metric['scoreUnit'] == unit and len(metric['rawData']) == 2 and all(len(fork) == 3 for fork in metric['rawData']), 'Changed JMH metric geometry')
            matching = [v[name]['value'] for k, v in bmf.items() if k.startswith(row['benchmark'] + ' ') and json.loads(k[len(row['benchmark']) + 1:]) == row['params']]
            require(matching == [metric['score']], 'BMF/JMH value mismatch')
            stats([metric['score']])
            metrics[name] = {'estimateKind': 'jmh-mean-score-over-2-forks-x-3-measurement-iterations',
                             'internalMedianSlotMeaning': 'JMH mean score, not a JVM trial median',
                             'median': metric['score'], 'observedSubsetP95': None, 'observed': 1, 'trials': 1,
                             'scoreError': metric.get('scoreError'), 'scoreConfidence': metric.get('scoreConfidence'), 'rawData': metric['rawData']}
        result[key] = {'count': 1, 'metrics': metrics}
    return result, None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('campaign', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='Fresh output directory; refuses overwrite')
    args = parser.parse_args()
    path = args.campaign.resolve()
    campaign = load(path / 'campaign.json')
    require(campaign['status'] == 'completed-execution-only-statistical-assessment-pending', 'Incomplete/failed campaign rejected')
    phase = campaign['phase']
    require(phase in ['pure', 'sdk'] and campaign['freeze']['plan'][phase]['pairOrder'] == ORDER, 'Frozen campaign order mismatch')
    jobs = JOBS if phase == 'pure' else WORKLOADS
    runs = campaign['runs']
    require([(r['job'], r['pair'], r['side']) for r in runs] == [(job, p, side) for job in jobs for p, order in enumerate(ORDER, 1) for side in order.split('-')], 'Missing/duplicate/out-of-order runs')
    evidence, provenance = {}, []
    for run in runs:
        require(run['status'] == 'passed-execution-and-evidence' and run['exitCode'] == 0 and not run['timedOut'], 'Unsuccessful invocation')
        command = load(Path(run['directory']) / 'command.json')
        require(command == run, 'Invocation record differs from frozen campaign')
        require(Path(run['directory']).resolve().is_relative_to(path), 'Foreign invocation directory')
        summary, identity = (pure if phase == 'pure' else sdk)(run, campaign['freeze'])
        evidence[(run['job'], run['pair'], run['side'])] = (summary, identity)
        provenance.append({'job': run['job'], 'pair': run['pair'], 'side': run['side'], 'raw': run['raw'], 'rawSha256': sha(run['raw']),
                           'commandRecordSha256': sha(Path(run['directory']) / 'command.json'), 'metrics': summary})
    comparisons = defaultdict(list)
    for job in jobs:
        for pair in range(1, 7):
            baseline, b_identity = evidence[(job, pair, 'baseline')]
            candidate, c_identity = evidence[(job, pair, 'candidate')]
            require(baseline.keys() == candidate.keys(), 'Paired cell mismatch')
            if phase == 'sdk':
                require(b_identity['corpora'] == c_identity['corpora'], 'Paired corpus evidence mismatch')
                for key in ['ide', 'ideVersion', 'java', 'javaHome', 'javaVendor', 'vm', 'os', 'arch', 'maxHeapBytes', 'processors']:
                    require(b_identity['environment'][key] == c_identity['environment'][key], 'Paired actual environment mismatch: ' + key)
            for cell_name in baseline:
                for metric in baseline[cell_name]['metrics']:
                    for statistic in (['median'] if phase == 'pure' else ['median', 'observedSubsetP95']):
                        b, c = baseline[cell_name]['metrics'][metric][statistic], candidate[cell_name]['metrics'][metric][statistic]
                        ratio = c / b if b is not None and b > 0 and c is not None else None
                        comparisons['|'.join([job, cell_name, metric, statistic])].append({'pair': pair, 'baseline': b, 'candidate': c,
                            'ratio': ratio, 'absoluteDelta': c - b if c is not None and b is not None else None,
                            'ratioUnavailableReason': None if ratio is not None else 'zero-baseline-or-unobserved',
                            'populationP95ClaimAllowed': statistic != 'observedSubsetP95' or baseline[cell_name]['metrics'][metric].get('populationP95ClaimAllowed', False) and candidate[cell_name]['metrics'][metric].get('populationP95ClaimAllowed', False)})
    table, increases, investigate = [], [], []
    for key, pairs in sorted(comparisons.items()):
        ratios = [p['ratio'] for p in pairs if p['ratio'] is not None]
        deltas = [p['absoluteDelta'] for p in pairs if p['absoluteDelta'] is not None]
        require(len(pairs) == 6, 'Six paired summaries required')
        entry = {'metric': key, 'estimateKind': 'jmh-mean-score' if phase == 'pure' else 'sdk-per-fixture-jvm-trial-summary', 'pairs': pairs, 'pairedMedianRatio': statistics.median(ratios) if len(ratios) == 6 else None,
                 'pairedMedianAbsoluteDelta': statistics.median(deltas) if len(deltas) == 6 else None,
                 'pairsWithIncrease': sum(p['absoluteDelta'] is not None and p['absoluteDelta'] > 0 for p in pairs),
                 'anyObservedIncrease': any(p['absoluteDelta'] is not None and p['absoluteDelta'] > 0 for p in pairs)}
        table.append(entry)
        if entry['anyObservedIncrease']:
            increases.append(key)
        if entry['pairedMedianRatio'] is not None and entry['pairedMedianRatio'] > 1.20:
            investigate.append(key)
    report = {'schema': 1, 'status': 'evidence-summary-main-judgment-required', 'performancePass': None,
              'campaign': str(path), 'campaignSha256': sha(path / 'campaign.json'), 'phase': phase,
              'comparisonUnit': 'six paired JMH invocations, each case/run score is the JMH mean over 2 forks x 3 measurement iterations' if phase == 'pure' else 'six paired fresh fixture JVMs, each repeated cell has 30 trials summarized by median and nearest-rank p95',
              'policy': 'Six paired comparison units; JMH uses mean scores in the internal median slot, SDK uses per-fixture JVM trial summaries. No fork/trial pooling or retries; p95 nearest rank on observed SDK trials; null/censor/unsupported separate; allocation scopes never added. All increases reported; >20% paired median flagged for investigation, never auto-waived or auto-failed.',
              'frozenInputs': campaign['freeze'], 'runs': provenance, 'comparisons': table,
              'anyIncrease': increases, 'over20PercentInvestigation': investigate}
    require(not args.output.exists(), 'Fresh report output required')
    args.output.mkdir(parents=True)
    (args.output / 'report.json').write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    lines = ['# Local comparison evidence', '', 'Status: main judgment required; no automatic performance pass.', '',
             ('JMH: six paired invocations. Each case/run estimate is the JMH mean score over 2 forks x 3 measurement iterations, not an individual JVM median. Forks and iterations are not additional independent pairs. The internal median field is only a shared storage slot.' if phase == 'pure' else 'SDK: six paired fresh fixture JVMs. Repeated cells use each JVM’s 30-trial median and nearest-rank p95 (rank 29 when all 30 are observed). Censored/null trials remain in JSON; resource cells have one observation per JVM.'), '',
             'No fork/trial pooling. Allocation scopes must not be summed. Table ratios and deltas are summarized across six paired run estimates.', '',
             '| Metric (JMH internal median = mean score; SDK = trial summary) | Median of six paired C/B ratios | Median of six paired estimate deltas | Pairs increased / 6 |', '|---|---:|---:|---:|']
    for entry in table:
        ratio = entry['pairedMedianRatio']
        lines.append(f'| {entry["metric"]} | {ratio if ratio is not None else "unavailable"} | {entry["pairedMedianAbsoluteDelta"]} | {entry["pairsWithIncrease"]} |')
    lines += ['', f'Observed increases: {len(increases)} metrics; >20% investigation: {len(investigate)} metrics.',
              '', 'See report.json for all six ratios, absolute deltas, raw JMH forks, per-run SDK metric values, null counts, overlap/outcome counts and exact input provenance.',
              '', 'Cancellation checkpoints differ by architecture. Edit restoration is a headless SDK observation upper bound with unknown worker origin; refusal censoring is not zero latency. Weak-GC deadline results do not establish total retained heap or a structural leak.']
    (args.output / 'report.md').write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
