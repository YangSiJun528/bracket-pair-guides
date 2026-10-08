#!/usr/bin/env python3
"""Local 16-invocation SDK setup check; defaults to plan only, never a performance verdict."""
import argparse
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def main():
    script = Path(__file__).resolve()
    spec = importlib.util.spec_from_file_location('issue97_local_comparison', script.with_name('run_local_comparison.py'))
    wrapper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(wrapper)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fingerprint-evidence', type=Path, default=wrapper.EVIDENCE / 'fingerprint-05.json')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--acknowledge-serial-idle', action='store_true')
    args = parser.parse_args()
    require = wrapper.require
    output = args.output.resolve()
    require(output.is_relative_to(wrapper.EVIDENCE), 'Output must remain in local issue97 redesign evidence')
    require(not args.execute or args.acknowledge_serial_idle, 'Explicit main-agent serial-idle acknowledgment required')
    roots = {'baseline': wrapper.BASELINE, 'candidate': wrapper.ROOT}
    sources = {side: wrapper.source_snapshot(root) for side, root in roots.items()}
    revisions = {side: wrapper.git(root, 'rev-parse', 'HEAD') for side, root in roots.items()}
    template_path = wrapper.EVIDENCE / 'sdk-final-smoke-07/commands.json'
    templates = wrapper.read(template_path)
    require(len(templates) == 16 and all(r['exitCode'] == 0 and r['status'] == 'passed-setup-only' for r in templates), 'Sixteen passing smoke07 templates required')
    require({(r['job'], r['side']) for r in templates} == {(job, side) for job in wrapper.WORKLOADS for side in roots}, 'Wrong template coverage')
    comparison = Path('com/sijunyang/bracketpairguides/comparison')
    for name in wrapper.COMMON:
        require((wrapper.ROOT / 'plugin/src/sdkPerformance/kotlin' / comparison / name).read_bytes() ==
                (wrapper.BASELINE / 'plugin/src/test/kotlin' / comparison / name).read_bytes(), 'Common source mismatch: ' + name)
    require((wrapper.ROOT / 'analysis-runtime/src/test/kotlin' / comparison / 'ComparisonHost.kt').read_bytes() ==
            (wrapper.BASELINE / 'plugin/src/test/kotlin' / comparison / 'ComparisonHost.kt').read_bytes(), 'Host protocol mismatch')
    manifest_path = output / 'preflight.json'
    if output.exists():
        require(args.execute and manifest_path.is_file(), 'Fresh directory required for a plan')
        plan = wrapper.read(manifest_path)
        require(plan['status'] == 'planned-not-run' and plan['sourcesBefore'] == sources and plan['revisions'] == revisions, 'No retry/resume or changed source allowed')
        require(plan['templateSha256'] == wrapper.sha(template_path) and plan['scriptSha256'] == wrapper.sha(script) and plan['wrapperSha256'] == wrapper.sha(script.with_name('run_local_comparison.py')), 'Prepared plan inputs changed')
        require(plan['fingerprintEvidence'] == str(args.fingerprint_evidence.resolve()), 'Fingerprint evidence changed')
    else:
        output.mkdir(parents=True)
        runs = []
        for template in templates:
            side, job = template['side'], template['job']
            require(Path(template['cwd']).resolve() == roots[side], 'Foreign template cwd')
            command = list(template['command'])
            for key, value in [('warmup', '1'), ('repeat', '5'), ('cancelTrials', '1')]:
                require([arg for arg in command if arg.startswith('-Dissue97.perf.' + key + '=')] == ['-Dissue97.perf.' + key + '=' + value], 'Setup geometry changed')
            directory = output / template['name']
            raw = directory / 'sdk.jsonl'
            for key, value in [('output', str(raw)), ('sourceRevision', revisions[side])]:
                prefix = '-Dissue97.perf.' + key + '='
                require(sum(arg.startswith(prefix) for arg in command) == 1, 'Missing/duplicate option: ' + key)
                command = [prefix + value if arg.startswith(prefix) else arg for arg in command]
            require('--no-daemon' in command, 'Owned single-use Gradle required')
            runs.append({'name': template['name'], 'job': job, 'side': side, 'cwd': str(roots[side]), 'command': command,
                         'directory': str(directory), 'raw': str(raw), 'timeoutSeconds': 900, 'status': 'not-run'})
        plan = {'schema': 1, 'status': 'planned-not-run', 'performancePass': None, 'createdUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
                'sourcesBefore': sources, 'revisions': revisions, 'templateSha256': wrapper.sha(template_path), 'scriptSha256': wrapper.sha(script),
                'wrapperSha256': wrapper.sha(script.with_name('run_local_comparison.py')), 'fingerprintEvidence': str(args.fingerprint_evidence.resolve()),
                'geometry': {'warmup': 1, 'repeat': 5, 'cancelTrials': 1}, 'runs': runs}
        wrapper.write(manifest_path, plan)
    if not args.execute:
        print(json.dumps({'status': plan['status'], 'manifest': str(manifest_path), 'invocations': 16}))
        return
    lock = wrapper.EVIDENCE / 'performance/.active-campaign.lock'
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump({'wrapperPid': os.getpid(), 'preflight': str(manifest_path)}, stream)
    plan['status'] = 'running'
    wrapper.write(manifest_path, plan)
    record = None
    try:
        pairs = {}
        for record in plan['runs']:
            try:
                wrapper.run_owned(record)
            finally:
                wrapper.copy_sdk_artifacts(record)
            require(record['exitCode'] == 0 and not record['timedOut'], 'SDK setup invocation failed; retain raw and inspect owned processes, no retry')
            directory = Path(record['directory'])
            xml_copy = directory / 'sdk-test.xml'
            artifacts = {a['copy']: a for a in record['artifactSnapshots']}
            require({'sdk-test.xml', 'descriptor-evidence.json', 'test-resources-evidence.json'} <= artifacts.keys(), 'Missing copied SDK evidence')
            require(artifacts['sdk-test.xml']['sourceMtime'] >= dt.datetime.fromisoformat(record['startedUtc']).timestamp(), 'Stale XML')
            suite = ET.parse(xml_copy).getroot()
            require([suite.get(key) for key in ['tests', 'failures', 'errors', 'skipped']] == ['1', '0', '0', '0'] and len(suite.findall('testcase')) == 1, 'SDK case did not execute and pass')
            environment, last, corpora = None, None, []
            with Path(record['raw']).open() as stream:
                for index, line in enumerate(stream, 1):
                    row = json.loads(line)
                    if index == 1:
                        environment = row
                    require(row['sequence'] == index and row['runId'] == environment['runId'], 'Mixed run/sequence')
                    if row['kind'] == 'corpus':
                        corpora.append({key: value for key, value in row.items() if key not in ['runId', 'sequence']})
                    last = row
            require(environment and environment['kind'] == 'environment' and last['kind'] == 'completed' and corpora, 'Incomplete SDK raw')
            require(environment['workload'] == last['workload'] == record['job'], 'Wrong workload')
            require((environment['warmups'], environment['repeats'], environment['cancelTrials']) == (1, 5, 1), 'Wrong setup counts')
            require(environment['sourceRevision'] == revisions[record['side']] and re.fullmatch('[0-9a-f]{40}', environment['sourceRevision']), 'Wrong full source revision')
            require(environment['ide'] == 'IC-241.19416.15' and environment['ideVersion'] == '2024.1.7', 'Wrong actual IDE')
            require(environment['java'].startswith('17.') and environment['javaVendor'] == 'JetBrains s.r.o.' and '/jbr/' in environment['javaHome'], 'Wrong actual JBR17')
            require(environment['maxHeapBytes'] == 2 * 1024 ** 3, 'Wrong heap')
            isolation = environment['descriptorIsolation']
            require(isolation['mode'] == 'manual-registry-events-no-auto-startup-pass', 'Wrong isolation mode')
            require(wrapper.sha(directory / 'descriptor-evidence.json') == isolation['evidenceSha256'] and wrapper.read(directory / 'descriptor-evidence.json') == isolation['evidence'], 'Actual descriptor evidence binding mismatch')
            record.update(environment=environment, corpusEvidence=corpora, status='passed-setup-only', rawSha256=wrapper.sha(record['raw']))
            for artifact in record['artifactSnapshots']:
                require(wrapper.sha(directory / artifact['copy']) == artifact['sha256'], 'Copied evidence changed')
            pairs.setdefault(record['job'], []).append(record)
            if len(pairs[record['job']]) == 2:
                wrapper.assess_pair('sdk', *pairs[record['job']])
            wrapper.write(directory / 'command.json', record)
            wrapper.write(manifest_path, plan)
        require(len(pairs) == 8 and all(len(records) == 2 for records in pairs.values()), 'Incomplete pairs')
        require({side: wrapper.source_snapshot(root) for side, root in roots.items()} == sources, 'Sources changed during setup')
        final = wrapper.freeze(args.fingerprint_evidence.resolve())
        require(final['candidateRevision'] == revisions['candidate'] and final['baselineRevision'] == revisions['baseline'], 'Revision changed during setup')
        for run in plan['runs']:
            require(run['environment']['harnessClassSha256'] == final['harnessEquivalence']['classSha256'][run['side']], 'Loaded harness does not match final metadata-only byte proof')
        plan.update(status='passed-setup-only', finalFreeze=final, sourcesUnchanged=True,
                    limitation='Setup coverage only; no CPU-idle, timing, allocation or formal performance pass claim')
    except BaseException as failure:
        plan.update(status='failed', failure=f'{type(failure).__name__}: {failure}', manualInspectionRequired=True)
        if record is not None:
            record.update(status='failed', failure=plan['failure'])
            if Path(record['directory']).exists():
                wrapper.write(Path(record['directory']) / 'command.json', record)
        raise
    finally:
        plan['sourcesAfter'] = {side: wrapper.source_snapshot(root) for side, root in roots.items()}
        plan['sourcesUnchanged'] = plan['sourcesAfter'] == sources
        plan['finishedUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        wrapper.write(manifest_path, plan)
        lock.unlink()
    print(json.dumps({'status': plan['status'], 'manifest': str(manifest_path), 'performancePass': None}))


if __name__ == '__main__':
    main()
