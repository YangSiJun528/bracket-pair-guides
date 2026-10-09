#!/usr/bin/env python3
"""Read-only review of a completed current JMH run; never launches measurements."""
import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

JOBS = ['sort-pair-events', 'sort-random', 'sort-ascending', 'sort-descending', 'cancellation', 'pairing', 'preferences']
BASELINE = '84d1a43141b1933c81e027ef01d1579fc4679e6e'

def read(path):
    return json.loads(path.read_text())

def key(row):
    return row['benchmark'], tuple(sorted(row.get('params', {}).items()))

def finite(value):
    return isinstance(value, (int, float)) and math.isfinite(value)

def classify(metric):
    interval = metric.get('conservativeDeltaInterval')
    if not interval or len(interval) != 2 or not all(finite(x) for x in interval):
        return 'interval-unavailable'
    if interval[0] > 0:
        return 'increase-beyond-reported-intervals'
    if interval[1] < 0:
        return 'decrease-beyond-reported-intervals'
    return 'direction-unresolved'

def assess(run, revision, repository):
    environment, state, summary = (read(run / name) for name in ('environment.json', 'state.json', 'summary.json'))
    issues, case_sets, profiles = [], {}, []
    def require(condition, message):
        if not condition:
            issues.append(message)
    require(state.get('status') == 'completed', 'Runner has not recorded completed state')
    repos = environment['repositories']
    require(repos['baseline']['head'] == BASELINE, 'Wrong frozen baseline revision')
    require(repos['candidate']['head'] == revision, 'Wrong candidate revision')
    require(environment.get('jobs') == JOBS, 'Job list differs from the seven declared jobs')
    require(environment.get('heapLauncherAndForks') == ['-Xms2g', '-Xmx2g'], 'Wrong configured heap')
    require(environment.get('jdkVersion') == '17.0.17', 'Wrong configured JDK patch version')
    require(environment.get('measurementLimitSeconds') == 240, 'Wrong existing execution limit')
    for side in ('baseline', 'candidate'):
        require(bool(repos[side].get('inputFileHashes')), side + ' measured source inventory missing')
        for relative, expected in repos[side].get('inputFileHashes', {}).items():
            proc = subprocess.run(['git', 'show', repos[side]['head'] + ':' + relative], cwd=repository, capture_output=True)
            require(proc.returncode == 0 and hashlib.sha256(proc.stdout).hexdigest() == expected,
                    side + ' measured source bytes differ from recorded commit: ' + relative)
        all_cases = []
        for job in JOBS:
            name = 'measure-' + side + '-' + job
            rows = read(run / 'steps' / name / 'results.json')
            identities = [key(row) for row in rows]
            require(bool(rows) and len(identities) == len(set(identities)), name + ' has missing/duplicate cases')
            all_cases.extend(identities)
            status = state['steps'][name]
            require(status.get('exitCode') == 0 and status.get('elapsedSeconds', math.inf) <= 240,
                    name + ' failed existing duration gate')
            for row in rows:
                require((row['forks'], row['warmupIterations'], row['measurementIterations']) == (2, 2, 3), name + ' wrong profile')
                require(row.get('warmupTime') == row.get('measurementTime') == '1 s', name + ' wrong iteration duration')
                require(row.get('jdkVersion') == '17.0.17' and row.get('jvm') == environment['java'], name + ' wrong actual fork JDK')
                require('-Xms2g' in row.get('jvmArgs', []) and '-Xmx2g' in row.get('jvmArgs', []), name + ' actual heap not reported')
                allocation = row.get('secondaryMetrics', {}).get('gc.alloc.rate.norm', {})
                require(finite(row['primaryMetric']['score']) and finite(allocation.get('score')), name + ' nonfinite latency/allocation score')
                require(allocation.get('scoreUnit') == 'B/op', name + ' allocation metric is not bytes/op')
            profiles.append({'side': side, 'job': job, 'cases': len(rows), 'elapsedSeconds': status.get('elapsedSeconds')})
        case_sets[side] = set(all_cases)
        require(len(all_cases) == len(case_sets[side]) == 46, side + ' full-profile coverage is not 46 distinct cases')
        require(state['steps'].get('coverage-' + side, {}).get('exitCode') == 0, side + ' canonical coverage check did not pass')
    require(case_sets['baseline'] == case_sets['candidate'], 'Baseline/candidate case identities differ')
    rows = summary.get('cases', [])
    require(len(rows) == 46 and {key(row) for row in rows} == case_sets['candidate'], 'Summary case identities differ from raw full-profile evidence')
    require(summary.get('executionDurationGate', {}).get('passes') is True, 'Summary duration gate is not passing')
    reviewed = []
    for row in rows:
        allocation = row['allocation']['gc.alloc.rate.norm']
        reviewed.append({'job': row['job'], 'benchmark': row['benchmark'], 'params': row['params'],
                         'latency': {**row['latency'], 'assessment': classify(row['latency'])},
                         'bytesPerOperation': {**allocation, 'assessment': classify(allocation)},
                         'latencyAboveExisting20PercentBoundary': finite(row['latency'].get('percentChange')) and row['latency']['percentChange'] > 20})
    counts = {metric: {label: sum(row[metric]['assessment'] == label for row in reviewed)
                      for label in ('increase-beyond-reported-intervals', 'decrease-beyond-reported-intervals', 'direction-unresolved', 'interval-unavailable')}
              for metric in ('latency', 'bytesPerOperation')}
    return {'evidenceIntegrityPassed': not issues, 'issues': issues, 'candidateHead': repos['candidate']['head'],
            'baselineHead': repos['baseline']['head'], 'profiles': profiles, 'assessmentCounts': counts, 'cases': reviewed,
            'latencyAbove20PercentCases': [row for row in reviewed if row['latencyAboveExisting20PercentBoundary']],
            'limitations': ['One full suite per revision; the two JVM forks are not repeated independent suites.',
                            'Conservative interval subtraction reports direction only; it is not a new hypothesis test or equivalence claim.',
                            'The local 20 percent indicator mirrors the existing Bencher latency boundary but does not execute its external historical gate.',
                            'Bytes/op is direct allocation evidence; MB/sec is throughput-dependent and cannot establish allocation parity.',
                            'These cases cover pairing, primitive sorting, cancellation and preferences only. No host capture/read locks, repair, snapshot queries, native inspection, presentation or end-to-end IDE latency is measured.',
                            'Candidate query range validation is outside these JMH cases; use pure query tests and IDE fixture evidence for that change.']}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--candidate-revision', required=True, help='Full 40-character final measurement commit SHA')
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.candidate_revision):
        parser.error('--candidate-revision must be the full 40-character final measurement commit SHA')
    try:
        result = assess(args.run.resolve(), args.candidate_revision, args.repository.resolve())
        print(json.dumps(result, indent=2, allow_nan=False))
        return 0 if result['evidenceIntegrityPassed'] else 1
    except (OSError, KeyError, ValueError, TypeError) as error:
        print(json.dumps({'evidenceIntegrityPassed': False, 'error': str(error)}))
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
