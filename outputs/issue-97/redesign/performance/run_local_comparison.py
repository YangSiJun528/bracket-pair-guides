#!/usr/bin/env python3
"""Local serial evidence wrapper. Default is a plan; never submits remote work.

Plan: --phase pure|sdk --output FRESH --plan-only
Execute that unchanged plan: --phase ... --output SAME --execute --acknowledge-serial-idle
Failed/running campaigns cannot resume or overwrite samples. A new campaign must
name a failed predecessor with --supersedes; retain both campaigns in reporting.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import struct
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASELINE = ROOT.parent.parent / 'issue-97-redesign-baseline' / ROOT.name
EVIDENCE = ROOT / 'outputs/issue-97/redesign'
JOBS = ['analyzeCold', 'analyzeReuse', 'visibleQuery', 'repair', 'cancelledAttempt']
WORKLOADS = ['analysis', 'write-wait', 'execution', 'repair', 'native', 'payload', 'capture-release', 'edit-restoration']
ORDER = ['baseline-candidate', 'candidate-baseline'] * 3
OWNERS = ['analysis-model', 'analysis-core', 'editor-ui', 'analysis-runtime', 'plugin']
PREFIX = 'com.sijunyang.bracketpairguides.comparison.'
COMMON = ['ComparisonFixture.kt', 'SdkComparisonMeasurementTest.kt', 'SdkNativeRepairWorkloads.kt',
          'SdkAllocationSegments.kt', 'ResourceEvidence.kt', 'SdkEvidencePump.kt',
          'SdkNativeControls.kt', 'SdkEditRestorationWorkload.kt', 'SdkDescriptorEvidence.kt']


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def source_snapshot(root):
    names = subprocess.check_output(['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
    files = {name: sha(root / name) for name in sorted(set(names))
             if name and not name.startswith('outputs/') and (root / name).is_file()}
    # Source files must not disappear from the freeze merely because a Git ignore matches.
    for owner in OWNERS + ['benchmarks', 'build-logic']:
        source = root / owner / 'src'
        if source.exists():
            for path in source.rglob('*'):
                if path.is_file():
                    files[str(path.relative_to(root))] = sha(path)
    return dict(sorted(files.items()))


def compiled_snapshot(root):
    """Only actual SDK classpath assets; production instrumentation is in selected jars."""
    files = {}
    def include(path):
        path = Path(path)
        if path.is_dir():
            for child in path.rglob('*'):
                if child.is_file():
                    include(child)
        elif path.is_file():
            files[str(path.relative_to(root))] = sha(path)
    candidate = root == ROOT
    for owner in (['editor-ui', 'analysis-runtime', 'plugin'] if candidate else ['plugin']):
        for language in ['java', 'kotlin']:
            include(root / owner / f'build/classes/{language}/test')
        if owner != 'plugin':
            include(root / owner / 'build/resources/test')
    if candidate:
        for language in ['java', 'kotlin']:
            include(root / f'plugin/build/classes/{language}/sdkPerformance')
    include(root / 'plugin/build/instrumented/instrumentTestCode')
    include(root / 'plugin/build/sdk-measurement/test-resources')
    proof = read(root / 'plugin/build/sdk-measurement/descriptor-evidence.json')
    selected = [Path(proof['originalJar']), Path(proof['measuredJar'])]
    for owner in OWNERS[:-1]:
        jars = [jar for jar in (root / owner / 'build/libs').glob('*.jar')
                if not jar.name.endswith(('-sources.jar', '-javadoc.jar'))]
        require(len(jars) == 1, f'Ambiguous actual owner archive: {root}/{owner}')
        selected += jars
    require(sha(proof['originalJar']) == proof['originalJarSha256'] and sha(proof['measuredJar']) == proof['measuredJarSha256'],
            'Prebuild final measurement descriptor producers before freezing')
    for jar in selected:
        require(jar.is_relative_to(root), 'Foreign selected SDK archive path')
        include(jar)
    include(root / 'plugin/build/sdk-measurement/descriptor-evidence.json')
    include(root / 'plugin/build/sdk-measurement/test-resources-evidence.json')
    return dict(sorted(files.items()))


def harness_equivalence(java):
    relative = Path('com/sijunyang/bracketpairguides/comparison/SdkComparisonMeasurementTest.class')
    baseline = BASELINE / 'plugin/build/instrumented/instrumentTestCode' / relative
    if not baseline.is_file():
        baseline = BASELINE / 'plugin/build/classes/kotlin/test' / relative
    candidate = ROOT / 'plugin/build/classes/kotlin/sdkPerformance' / relative
    old, new = b'bracket-pair-guides_test', b'bracket-pair-guides_sdkPerformance'
    constant = lambda value: b'\x01' + struct.pack('>H', len(value)) + value
    before, after = baseline.read_bytes(), candidate.read_bytes()
    require(before.count(constant(old)) == 1, 'Expected exactly one baseline module-name UTF8 constant')
    require(before.replace(constant(old), constant(new), 1) == after,
            'Shared harness differs beyond the single Kotlin metadata module-name constant')
    javap = Path(java).parent / 'javap'
    code = [subprocess.check_output([str(javap), '-p', '-c', '-s', str(path)]) for path in [baseline, candidate]]
    require(code[0] == code[1], 'Shared harness executable disassembly differs')
    return {'classSha256': {'baseline': sha(baseline), 'candidate': sha(candidate)},
            'classPaths': {'baseline': str(baseline), 'candidate': str(candidate)},
            'approvedDifference': 'Exactly one CONSTANT_Utf8 Kotlin.Metadata d2 module-name value and its length; every other classfile byte identical',
            'baselineModule': old.decode(), 'candidateModule': new.decode(),
            'singleConstantSubstitutionProducesExactCandidateBytes': True,
            'javapCommand': [str(javap), '-p', '-c', '-s'],
            'executableDisassemblySha256': hashlib.sha256(code[0]).hexdigest(),
            'executableDisassemblyIdentical': True}


def freeze(fingerprint_path):
    plan = read(EVIDENCE / 'performance-plan.json')
    require(plan['pure']['pairOrder'] == ORDER and plan['sdk']['pairOrder'] == ORDER, 'Pair order differs from preregistration')
    require(plan['pure']['freshJvmPairs'] == plan['sdk']['freshJvmPairs'] == 6, 'Six pairs required')
    require(plan['sdk']['categories'] == WORKLOADS, 'Eight exact SDK workloads required')
    require((plan['sdk']['warmup'], plan['sdk']['repeat'], plan['sdk']['cancellationTrials']) == (100, 30, 30), 'SDK counts changed')
    pure = plan['pure']
    require((pure['forks'], pure['threads'], pure['warmupIterations'], pure['warmupSeconds'], pure['measurementIterations'], pure['measurementSeconds'], pure['perJobTimeoutSeconds']) == (2, 1, 2, 1, 3, 1, 240), 'JMH geometry/budget changed')
    baseline_revision = git(BASELINE, 'rev-parse', '072533f')
    production = ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties']
    production += [f'{owner}/{part}' for owner in OWNERS for part in ['src/main', 'build.gradle.kts']]
    subprocess.run(['git', '-C', str(BASELINE), 'diff', '--exit-code', baseline_revision, '--', *production], check=True, stdout=subprocess.PIPE)
    subprocess.run(['git', '-C', str(ROOT), 'diff', '--exit-code', 'HEAD', '--', *production], check=True, stdout=subprocess.PIPE)
    comparison = Path('com/sijunyang/bracketpairguides/comparison')
    common = {}
    for name in COMMON:
        candidate = ROOT / 'plugin/src/sdkPerformance/kotlin' / comparison / name
        baseline = BASELINE / 'plugin/src/test/kotlin' / comparison / name
        require(candidate.read_bytes() == baseline.read_bytes(), f'Common source mismatch: {name}')
        common[name] = sha(candidate)
    host = Path('analysis-runtime/src/test/kotlin') / comparison / 'ComparisonHost.kt'
    baseline_host = BASELINE / 'plugin/src/test/kotlin' / comparison / 'ComparisonHost.kt'
    require((ROOT / host).read_bytes() == baseline_host.read_bytes(), 'Common host protocol mismatch')
    common['ComparisonHost.kt'] = sha(ROOT / host)
    fingerprint = read(fingerprint_path)
    original_fingerprint = read(EVIDENCE / 'fingerprint-01.json')
    require(fingerprint['java'] == original_fingerprint['java'] and all(fingerprint['sides'][side]['jar'] == original_fingerprint['sides'][side]['jar'] for side in ['baseline', 'candidate']), 'Frozen Java/JMH jar locations changed')
    require(fingerprint['equal'] is True, 'Semantic fingerprint prerequisite missing')
    java = Path(fingerprint['java'])
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    require('Temurin-17.0.17+10' in version, 'Frozen JMH Java differs')
    jars = {side: {'path': entry['jar'], 'sha256': sha(entry['jar'])} for side, entry in fingerprint['sides'].items()}
    for side in ['baseline', 'candidate']:
        require(jars[side]['sha256'] == fingerprint['sides'][side]['sha256'], f'{side} JMH jar changed since fingerprint01; refresh exact semantic proof first')
    templates_path = EVIDENCE / 'sdk-isolated-smoke-04/commands.json'
    templates = read(templates_path)
    require(len(templates) == 16 and all(record['exitCode'] == 0 for record in templates), 'All sixteen isolated templates must have passed')
    return {'baselineRevision': baseline_revision, 'candidateRevision': git(ROOT, 'rev-parse', 'HEAD'),
            'plan': plan, 'planSha256': sha(EVIDENCE / 'performance-plan.json'),
            'fingerprintEvidencePath': str(fingerprint_path), 'fingerprintEvidenceSha256': sha(fingerprint_path),
            'wrapperSha256': sha(__file__), 'commonSources': common,
            'java': str(java), 'javaSha256': sha(java), 'javaVersion': version, 'jars': jars,
            'harnessEquivalence': harness_equivalence(java),
            'sdkTemplateSha256': sha(templates_path), 'sdkTemplates': templates,
            'sources': {side: source_snapshot(root) for side, root in [('baseline', BASELINE), ('candidate', ROOT)]},
            'compiledInputs': {side: compiled_snapshot(root) for side, root in [('baseline', BASELINE), ('candidate', ROOT)]}}


def commands(phase, output, frozen):
    runs = []
    for job in JOBS if phase == 'pure' else WORKLOADS:
        for pair, order in enumerate(ORDER, 1):
            for side in order.split('-'):
                directory = output / job / f'pair-{pair:02d}' / side
                raw = directory / ('jmh.json' if phase == 'pure' else 'sdk.jsonl')
                if phase == 'pure':
                    command = [frozen['java'], '-jar', frozen['jars'][side]['path'],
                               '^' + re.escape(f'com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark.{job}') + '$',
                               '-bm', 'avgt', '-wi', '2', '-w', '1s', '-i', '3', '-r', '1s', '-f', '2', '-t', '1',
                               '-jvmArgs', '-Xms2g -Xmx2g', '-prof', 'gc', '-foe', 'true', '-rf', 'json', '-rff', str(raw)]
                    cwd = ROOT
                    timeout = 240
                else:
                    template = next(item for item in frozen['sdkTemplates'] if item['name'] == f'{job}-{side}')
                    command = list(template['command'])
                    replacements = {'output': str(raw), 'sourceRevision': frozen[f'{side}Revision'], 'warmup': '100', 'repeat': '30', 'cancelTrials': '30'}
                    for key, value in replacements.items():
                        prefix = f'-Dissue97.perf.{key}='
                        require(sum(arg.startswith(prefix) for arg in command) == 1, f'Missing/duplicate template option {key}')
                        command = [prefix + value if arg.startswith(prefix) else arg for arg in command]
                    require('--no-daemon' in command, 'SDK must own its single-use Gradle invocation')
                    cwd = Path(template['cwd'])
                    timeout = 900  # >= fixture600 + cleanup30 + SDK/Gradle startup; never a reduced sample budget.
                runs.append({'job': job, 'pair': pair, 'side': side, 'cwd': str(cwd), 'command': command,
                             'timeoutSeconds': timeout, 'directory': str(directory), 'raw': str(raw), 'status': 'not-yet-run'})
    return runs


def terminate_owned(process):
    """New session guarantees this group was created by this wrapper, not a user daemon."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    # The parent can exit while a JMH fork remains; always address the owned group.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=10)


def run_owned(record):
    directory = Path(record['directory'])
    directory.mkdir(parents=True, exist_ok=False)
    log = directory / 'process.log'
    record.update(status='running', startedUtc=dt.datetime.now(dt.timezone.utc).isoformat())
    write(directory / 'command.json', record)
    with log.open('xb') as stream:
        process = subprocess.Popen(record['command'], cwd=record['cwd'], stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        record['ownedPid'] = record['ownedProcessGroup'] = process.pid
        started = time.monotonic()
        try:
            process.wait(timeout=record['timeoutSeconds'])
            record['timedOut'] = False
            if process.returncode != 0:
                terminate_owned(process)
        except subprocess.TimeoutExpired:
            record['timedOut'] = True
            terminate_owned(process)
            record['manualInspectionRequired'] = True
        except BaseException:
            terminate_owned(process)
            record.update(interrupted=True, manualInspectionRequired=True)
            raise
        finally:
            record['elapsedSeconds'] = time.monotonic() - started
            record['exitCode'] = process.returncode
            record['finishedUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
            write(directory / 'command.json', record)


def copy_sdk_artifacts(record):
    directory, cwd = Path(record['directory']), Path(record['cwd'])
    if not directory.exists():
        return
    task = 'sdkPerformance' if record['side'] == 'candidate' else 'test'
    sources = [(cwd / f'plugin/build/test-results/{task}/TEST-{PREFIX}SdkComparisonMeasurementTest.xml', 'sdk-test.xml')]
    sources += [(cwd / 'plugin/build/sdk-measurement' / name, name)
                for name in ['descriptor-evidence.json', 'test-resources-evidence.json']]
    record['artifactSnapshots'] = []
    for source, name in sources:
        if source.is_file():
            shutil.copy2(source, directory / name)
            record['artifactSnapshots'].append({'source': str(source), 'copy': name, 'sha256': sha(directory / name),
                                                'sourceMtime': source.stat().st_mtime, 'validated': False})


def sdk_evidence(record, frozen):
    directory, cwd = Path(record['directory']), Path(record['cwd'])
    task = 'sdkPerformance' if record['side'] == 'candidate' else 'test'
    xml = cwd / f'plugin/build/test-results/{task}/TEST-{PREFIX}SdkComparisonMeasurementTest.xml'
    require(xml.is_file(), 'Actual SDK XML missing')
    require(xml.stat().st_mtime >= dt.datetime.fromisoformat(record['startedUtc']).timestamp(), 'SDK XML predates this invocation')
    suite = ET.parse(directory / 'sdk-test.xml').getroot()
    require((suite.get('tests'), suite.get('failures'), suite.get('errors'), suite.get('skipped')) == ('1', '0', '0', '0'), 'SDK fixture did not execute exactly one passing case')
    require(len(suite.findall('testcase')) == 1, 'SDK actual case missing')
    environment, last = None, None
    corpora = []
    with Path(record['raw']).open() as stream:
        for index, line in enumerate(stream, 1):
            row = json.loads(line)
            if index == 1:
                environment = row
            require(row['runId'] == environment['runId'] and row['sequence'] == index, 'Mixed SDK runs or sequences')
            if row['kind'] == 'corpus':
                corpora.append({key: value for key, value in row.items() if key not in ['runId', 'sequence']})
            last = row
    require(corpora, 'SDK corpus identity evidence missing')
    record['corpusEvidence'] = corpora
    require(environment and environment['kind'] == 'environment' and last['kind'] == 'completed', 'SDK raw is incomplete')
    require(last['workload'] == environment['workload'] == record['job'], 'Wrong SDK workload')
    require((environment['warmups'], environment['repeats'], environment['cancelTrials']) == (100, 30, 30), 'Smoke counts leaked into formal SDK run')
    require(environment['sourceRevision'] == frozen[f'{record["side"]}Revision'], 'SDK source provenance changed')
    require(environment['harnessClassSha256'] == frozen['harnessEquivalence']['classSha256'][record['side']], 'Actual loaded harness differs from frozen side bytes')
    require(environment['ide'] == 'IC-241.19416.15' and environment['ideVersion'] == '2024.1.7', 'Wrong actual SDK')
    require(environment['descriptorIsolation']['mode'] == 'manual-registry-events-no-auto-startup-pass', 'Unisolated SDK scope')
    require(sha(directory / 'descriptor-evidence.json') == environment['descriptorIsolation']['evidenceSha256'], 'Copied descriptor evidence differs from actual SDK evidence')
    require(read(directory / 'descriptor-evidence.json') == environment['descriptorIsolation']['evidence'], 'Descriptor evidence content binding differs')
    require(environment['maxHeapBytes'] == 2 * 1024 ** 3, 'Actual SDK maximum heap changed')
    record['testResourcesEvidenceSha256'] = sha(directory / 'test-resources-evidence.json')
    record['environment'] = environment
    record['xmlSha256'] = sha(directory / 'sdk-test.xml')
    for artifact in record['artifactSnapshots']:
        artifact['validated'] = artifact['copy'] in ['sdk-test.xml', 'descriptor-evidence.json']


def assess_pair(phase, prior, current):
    if phase == 'sdk':
        keys = ['ide', 'ideVersion', 'java', 'javaHome', 'javaVendor', 'vm', 'os', 'arch', 'maxHeapBytes', 'processors']
        for key in keys:
            require(prior['environment'][key] == current['environment'][key], f'Actual SDK pair environment differs: {key}')
        require(prior['corpusEvidence'] == current['corpusEvidence'], 'Actual SDK pair corpus/source/geometry evidence differs')
        heaps = lambda env: [arg for arg in env['jvmArgs'] if arg.startswith(('-Xms', '-Xmx'))]
        require(heaps(prior['environment']) == heaps(current['environment']), 'Actual SDK pair heap flags differ')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=['pure', 'sdk'], required=True)
    parser.add_argument('--fingerprint-evidence', type=Path, default=EVIDENCE / 'fingerprint-01.json', help='Fresh exact semantic proof for final jars; preserve prior fingerprint evidence and jar locations')
    parser.add_argument('--output', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--plan-only', '--dry-run', action='store_true')
    mode.add_argument('--execute', action='store_true')
    parser.add_argument('--acknowledge-serial-idle', action='store_true', help='Main confirms all other builds/Driver/Qodana/verifiers/measurements finished; wrapper does not infer CPU idleness')
    parser.add_argument('--supersedes', type=Path, help='New campaign explicitly retains and reports an earlier failed campaign; never retries/overwrites its samples')
    args = parser.parse_args()
    output = args.output.resolve()
    require(output.is_relative_to(EVIDENCE / 'performance'), 'Campaign must be inside local issue97 performance evidence')
    require(not args.execute or args.acknowledge_serial_idle, 'Explicit serial-idle assertion required to execute')
    current = freeze(args.fingerprint_evidence.resolve())  # Before ANY timed process; no hashing/scanning during samples.
    manifest_path = output / 'campaign.json'
    if output.exists():
        require(args.execute and manifest_path.is_file(), 'Fresh output directory required for a plan')
        campaign = read(manifest_path)
        require(campaign['phase'] == args.phase and campaign['status'] == 'planned', 'No automatic resume/retry of running, failed or completed measurements')
        require(campaign['freeze'] == current, 'Inputs changed since plan; do not reuse its output directory')
    else:
        output.mkdir(parents=True)
        supersedes = None
        if args.supersedes:
            previous = args.supersedes.resolve()
            require(read(previous / 'campaign.json')['status'] == 'failed', 'Only an explicitly failed prior campaign can be superseded')
            supersedes = str(previous)
        campaign = {'schema': 1, 'phase': args.phase, 'status': 'planned', 'notYetRun': True,
                    'createdUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'freeze': current,
                    'supersedesFailedCampaign': supersedes, 'resumptionPolicy': 'No selective retry; a failed campaign stays failed. Report all earlier failed campaigns.',
                    'runs': commands(args.phase, output, current)}
        write(manifest_path, campaign)
    if not args.execute:
        print(json.dumps({'status': 'not-yet-run', 'manifest': str(manifest_path), 'invocations': len(campaign['runs'])}))
        return
    lock = EVIDENCE / 'performance/.active-campaign.lock'
    lock_fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(lock_fd, 'w') as stream:
        json.dump({'wrapperPid': os.getpid(), 'campaign': str(manifest_path)}, stream)
    campaign.update(status='running', notYetRun=False, serialIdleAssertedByMain=True)
    write(manifest_path, campaign)
    previous = None
    record = None
    try:
        for record in campaign['runs']:
            try:
                run_owned(record)
            finally:
                if args.phase == 'sdk':
                    copy_sdk_artifacts(record)
            require(not record['timedOut'] and record['exitCode'] == 0, f'Failed/timed out {record["job"]} pair{record["pair"]} {record["side"]}; inspect owned process evidence before any new campaign')
            require(Path(record['raw']).is_file() and Path(record['raw']).stat().st_size > 0, 'Measurement raw output missing')
            record['status'] = 'execution-completed-evidence-pending'
            write(Path(record['directory']) / 'command.json', record)
            if previous and (previous['job'], previous['pair']) == (record['job'], record['pair']):
                for completed in [previous, record]:
                    if args.phase == 'pure':
                        bmf = Path(completed['directory']) / 'bmf.json'
                        with bmf.open('xb') as stream:
                            subprocess.run(['jq', '--arg', 'job', completed['job'], '-f', str(ROOT / 'benchmarks/runner/metrics.jq'), completed['raw']], stdout=stream, check=True)
                    else:
                        sdk_evidence(completed, current)
                        outcomes = []
                        for line in (Path(completed['directory']) / 'process.log').read_text(errors='replace').splitlines():
                            match = re.match(r'> Task (:\S+)(?: (.*))?$', line)
                            if match and re.search(r':(?:compile\w*|process\w*Resources|jar|composedJar|instrument\w*|patchPluginXml|prepareSdkMeasurement\w*)$', match[1]):
                                outcomes.append({'task': match[1], 'outcome': match[2] or 'EXECUTED'})
                        completed['producerTaskOutcomes'] = outcomes
                        executed = [item for item in outcomes if item['outcome'] not in ['UP-TO-DATE', 'SKIPPED', 'NO-SOURCE']]
                        if executed:
                            side = completed['side']
                            root = ROOT if side == 'candidate' else BASELINE
                            require(compiled_snapshot(root) == current['compiledInputs'][side], 'Actual SDK producer changed frozen measurement input bytes')
                            require(not executed, 'Prebuilt producer prerequisite failed; raw retained: ' + str(executed))
                    completed['status'] = 'passed-execution-and-evidence'
                    write(Path(completed['directory']) / 'command.json', completed)
                assess_pair(args.phase, previous, record)
                write(manifest_path, campaign)
                previous = None
            else:
                previous = record
        require(freeze(args.fingerprint_evidence.resolve()) == current, 'Input freeze changed during campaign; retain raw data as invalid, never claim a pass')
        campaign['status'] = 'completed-execution-only-statistical-assessment-pending'
    except BaseException as failure:
        campaign.update(status='failed', failure=f'{type(failure).__name__}: {failure}', manualInspectionRequired=True)
        if record is not None:
            record.update(status='failed', failure=campaign['failure'])
            if Path(record['directory']).exists():
                write(Path(record['directory']) / 'command.json', record)
        write(manifest_path, campaign)
        raise
    finally:
        campaign['finishedUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        write(manifest_path, campaign)
        lock.unlink()
    print(json.dumps({'status': campaign['status'], 'manifest': str(manifest_path)}))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(f'Campaign stopped: {failure}', file=sys.stderr)
        sys.exit(1)
