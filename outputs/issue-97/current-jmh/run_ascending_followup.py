#!/usr/bin/env python3
"""Predetermined two balanced ascending-sort pairs; executes no builds, never replaces original evidence."""
import argparse
import importlib.util
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('current_jmh_runner', BASE / 'run_jmh.py')
jmh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jmh)
CANDIDATE = 'f7599fa39be7d8e93cc339da719b14012ab1a1dd'
ORDER = [(1, 'candidate'), (1, 'baseline'), (2, 'baseline'), (2, 'candidate')]

def read(path):
    return json.loads(path.read_text())

def validate_sources(original):
    repos = original['repositories']
    if repos['candidate']['head'] != CANDIDATE or repos['baseline']['head'] != jmh.FROZEN_BASELINE:
        raise ValueError('Original run has unexpected candidate/baseline commit')
    candidate_root = Path(repos['candidate']['root'])
    for side in ('baseline', 'candidate'):
        current = jmh.repository_info(Path(repos[side]['root']), candidate_root)
        if current['head'] != repos[side]['head'] or current['inputFileHashes'] != repos[side]['inputFileHashes']:
            raise ValueError(side + ' source/build fingerprints changed since original full run')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, default=BASE / 'results')
    parser.add_argument('--output', type=Path, required=True, help='Fresh non-existing follow-up directory')
    parser.add_argument('--execute', action='store_true', help='Default only verifies inputs and prints the fixed plan')
    args = parser.parse_args()
    original, output = args.original.resolve(), args.output.resolve()
    if output.exists():
        parser.error('Output directory must not exist; completed/failed evidence is never overwritten')
    if output == original or output.is_relative_to(original):
        parser.error('Follow-up output must be separate from immutable original run')
    environment = read(original / 'environment.json')
    if read(original / 'state.json').get('status') != 'completed':
        parser.error('Original full run must be completed')
    validate_sources(environment)
    bundles, expected_cases, fingerprints = {}, {}, {}
    for side in ('baseline', 'candidate'):
        bundle = original / 'bundles' / side / 'sort-ascending'
        metadata = read(bundle / 'bundle.json')
        if metadata.get('side') != side or metadata.get('job') != 'sort-ascending' or metadata.get('smoke'):
            raise ValueError('Wrong original full-profile bundle: ' + side)
        jmh.portable_args(bundle / 'run.args', smoke=False)
        hashes = {name: jmh.digest(bundle / name) for name in ('benchmarks.jar', 'run.args', 'bundle.json')}
        if hashes['benchmarks.jar'] != metadata['jarSha256'] or hashes['run.args'] != metadata['argumentsSha256']:
            raise ValueError('Original immutable bundle changed: ' + side)
        bundles[side], fingerprints[side] = bundle, hashes
        rows = read(original / 'steps' / ('measure-' + side + '-sort-ascending') / 'results.json')
        expected_cases[side] = {jmh.identity(row) for row in rows}
        if len(rows) != len(expected_cases[side]) or len(rows) != 8 or any(row.get('params', {}).get('distribution') != 'ascending' for row in rows):
            raise ValueError('Original ascending job is not eight distinct ascending cases')
    if expected_cases['baseline'] != expected_cases['candidate']:
        raise ValueError('Original ascending before/after identities differ')
    plan = {'candidate': CANDIDATE, 'baseline': jmh.FROZEN_BASELINE, 'original': str(original),
            'order': [{'pair': pair, 'side': side} for pair, side in ORDER], 'casesPerInvocation': 8,
            'fixedProfile': {'jdk': str(jmh.JAVA), 'heap': jmh.HEAP, 'forks': 2, 'warmups': 2, 'measurements': 3, 'iterationSeconds': 1, 'profiler': 'gc', 'timeoutSeconds': 240},
            'originalBundleHashes': fingerprints, 'sharedRunnerSha256': jmh.digest(BASE / 'run_jmh.py'),
            'scope': 'Predetermined two paired ascending jobs to assess reproducibility of original 32768-sort signal; original result retained, no repeated-until-pass rule'}
    print(json.dumps(plan, indent=2), flush=True)
    if not args.execute:
        return 0
    runner = jmh.Runner(output, resume=False)
    jmh.write_json(output / 'plan.json', plan)
    jmh.write_json(output / 'original-environment.json', environment)
    (output / 'run_ascending_followup.py').write_bytes(Path(__file__).read_bytes())
    (output / 'shared-run_jmh.py').write_bytes((BASE / 'run_jmh.py').read_bytes())
    results = {}
    for pair, side in ORDER:
        validate_sources(environment)
        for name, fingerprint in fingerprints[side].items():
            if jmh.digest(bundles[side] / name) != fingerprint:
                raise ValueError('Original bundle changed during follow-up')
        name = f'measure-pair-{pair}-{side}-sort-ascending'
        directory = output / 'steps' / name
        command = [str(jmh.JAVA), *jmh.HEAP, '@run.args', '-jvm', str(jmh.JAVA), '-jvmArgsAppend', ' '.join(jmh.HEAP)]
        runner.run(name, command, directory, 240, bundle=bundles[side])
        rows = read(directory / 'results.json')
        if len(rows) != 8 or {jmh.identity(row) for row in rows} != expected_cases[side]:
            raise ValueError('Follow-up lost or duplicated ascending cases')
        for row in rows:
            jmh.verify_fork(row)
            if (row['forks'], row['warmupIterations'], row['measurementIterations']) != (2, 2, 3) or row.get('warmupTime') != '1 s' or row.get('measurementTime') != '1 s':
                raise ValueError('Follow-up profile changed')
            if 'gc.alloc.rate.norm' not in row.get('secondaryMetrics', {}):
                raise ValueError('Follow-up has no bytes/op profile')
        results[pair, side] = {jmh.identity(row): row for row in rows}
    validate_sources(environment)
    pairs = []
    for pair in (1, 2):
        cases = []
        for identity in sorted(expected_cases['baseline']):
            baseline, candidate = results[pair, 'baseline'][identity], results[pair, 'candidate'][identity]
            cases.append({'benchmark': identity[0], 'params': dict(identity[1]),
                          'latency': jmh.comparison(baseline['primaryMetric'], candidate['primaryMetric']),
                          'bytesPerOperation': jmh.comparison(baseline['secondaryMetrics']['gc.alloc.rate.norm'], candidate['secondaryMetrics']['gc.alloc.rate.norm'])})
        pairs.append({'pair': pair, 'order': [side for number, side in ORDER if number == pair], 'cases': cases})
    jmh.write_json(output / 'summary.json', {'originalRetained': str(original / 'summary.json'), 'pairs': pairs,
                                           'limitations': 'Two predetermined paired repetitions; report each pair and original signal, never substitute follow-up for original or pool different cases.'})
    runner.state.update(status='completed', completedUtc=jmh.now())
    runner.save()
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
