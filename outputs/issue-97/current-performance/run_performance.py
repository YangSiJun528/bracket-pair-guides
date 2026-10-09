#!/usr/bin/env python3
"""Sequential fixture runner. No workloads run without --execute; JMH is excluded."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
import platform
import re
import signal
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GATES = {
    'analysis': ('AnalysisBaselineMeasurementTest', 'issue93.measure', 'issue93.measure.output'),
    'write-wait': ('AnalysisWriteWaitMeasurementTest', 'issue93.measure', 'issue93.measure.writeWait.output'),
    'execution': ('EditorExecutionMeasurementTest', 'issue93.measure.execution', 'issue93.measure.execution.output'),
    'repair': ('GuideRepairMeasurementTest', 'issue93.measure.guideRepair', 'issue93.measure.guideRepair.output'),
    'native': ('NativeConflictMeasurementTest', 'issue93.measure.native', 'issue93.measure.native.output'),
    'payload': ('AnalysisPayloadMeasurementTest', 'issue93.measure.payload', 'issue93.measure.payload.output'),
    'capture-release': ('CaptureReleaseMeasurementTest', 'issue93.measure.captureRelease', 'issue93.measure.captureRelease.output'),
}


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def save(path, state):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(state, indent=2) + '\n')
    temp.replace(path)


EXTERNAL_MARKERS = (
    'org.gradle.wrapper.GradleWrapperMain', 'Gradle Test Executor', 'GradleDaemon', 'GradleWorkerMain',
    'org.openjdk.jmh.Main', 'benchmarks.jar', 'org.jetbrains.intellij.platform.gradle.artifacts.transform',
    'com.intellij.idea.Main', 'com.intellij.idea.ApplicationLoader', 'com.jetbrains.pluginverifier',
    'plugin-verifier', 'PluginVerifierMain', 'ide-starter', 'starter-squashed', 'driver-client',
    'qodana scan', 'docker run', 'docker exec',
)
COORDINATION_LOG = ROOT / 'coordination-observations.jsonl'
SENSITIVE_JVM_ENVIRONMENT = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JMH_JVM_ARGS', 'JAVA_OPTS', 'GRADLE_OPTS')


def sensitive_environment():
    present = [name for name in SENSITIVE_JVM_ENVIRONMENT if os.environ.get(name)]
    if present:
        raise ValueError('Inherited JVM option variables are set; refuse measurement: ' + str(present))
    return {name: 'unset' for name in SENSITIVE_JVM_ENVIRONMENT}


# Exact processes observed before this task; never terminate them.
# PID, parent, executable, and start time must match. Worker CPU must be 0.0%;
# the known daemon may use <=0.1% only with independent last-log-state IDLE evidence.
PRESERVED_IDLE_PROCESSES = {
    33144: {'parentPid': 1, 'startTime': 'Wed Oct 7 12:26:09 2026',
            'executable': '/Users/sijun-yang/.sdkman/candidates/java/25.0.3-amzn/bin/java', 'role': 'GradleDaemon'},
    33413: {'parentPid': 33144, 'startTime': 'Wed Oct 7 12:27:10 2026',
            'executable': '/Users/sijun-yang/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home/bin/java', 'role': 'GradleWorkerMain'},
}


PRESERVED_DAEMON_LOG = Path('/Users/sijun-yang/.gradle/daemon/9.8.0/daemon-33144.out.log')
PRESERVED_DAEMON_LOG_READ_LIMIT = 4 * 1024 * 1024


def preserved_daemon_log_state():
    """Read at most 4 MiB; expose only validated state/time and bounded-byte provenance."""
    evidence = {'path': str(PRESERVED_DAEMON_LOG), 'state': 'unknown',
                'parser': 'DaemonRegistryUpdater marking transition v1',
                'readLimitBytes': PRESERVED_DAEMON_LOG_READ_LIMIT}
    try:
        with PRESERVED_DAEMON_LOG.open('rb') as stream:
            before = os.fstat(stream.fileno())
            offset = max(0, before.st_size - PRESERVED_DAEMON_LOG_READ_LIMIT)
            stream.seek(offset)
            data = stream.read(PRESERVED_DAEMON_LOG_READ_LIMIT)
            after = os.fstat(stream.fileno())
        evidence.update(fileSizeBytes=before.st_size, readOffsetBytes=offset,
                        readBytes=len(data), boundedBytesSha256=hashlib.sha256(data).hexdigest(),
                        fileModifiedNs=before.st_mtime_ns)
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            evidence['state'] = 'changed-during-read'
            return evidence
        lines = data.decode('utf-8', errors='replace').splitlines()
        if offset:
            lines = lines[1:]  # A bounded tail may start in the middle of a line.
        markers = [line for line in lines if '[org.gradle.launcher.daemon.server.DaemonRegistryUpdater]' in line
                   and 'Marking the daemon as' in line]
        evidence['transitionMarkersObserved'] = len(markers)
        if not markers:
            evidence['state'] = 'missing-transition'
            return evidence
        pattern = r'^(\S+)\s+\[(?:DEBUG|INFO)\]\s+\[org\.gradle\.launcher\.daemon\.server\.DaemonRegistryUpdater\]\s+Marking the daemon as (idle|busy)(?:,|\s|$)'
        matches = [re.match(pattern, line) for line in markers]
        latest = matches[-1]
        if latest is None:
            evidence['state'] = 'ambiguous-transition'
            return evidence
        timestamp, state = latest.groups()
        parsed = datetime.datetime.fromisoformat(timestamp)
        if parsed.tzinfo is None:
            evidence['state'] = 'ambiguous-timestamp'
            return evidence
        if any(match is not None and match.group(1) == timestamp and match.group(2) != state for match in matches):
            evidence['state'] = 'ambiguous-transition'
            return evidence
        evidence.update(state=state, timestamp=timestamp)
    except FileNotFoundError:
        evidence['state'] = 'missing-log'
    except (OSError, ValueError):
        evidence['state'] = 'unreadable-or-invalid-log'
    return evidence


def preserved_process_snapshot():
    result = subprocess.run(['ps', '-p', ','.join(map(str, PRESERVED_IDLE_PROCESSES)),
                             '-o', 'pid=,ppid=,%cpu=,lstart=,comm='], capture_output=True, text=True, timeout=10)
    if result.returncode not in (0, 1):
        raise RuntimeError('Could not inspect preserved process CPU/identity fields')
    found = {}
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 8)
        if len(fields) != 9:
            raise RuntimeError('Unexpected preserved process identity format')
        pid = int(fields[0])
        expected = PRESERVED_IDLE_PROCESSES[pid]
        row = {'pid': pid, 'parentPid': int(fields[1]), 'cpuPercent': float(fields[2]),
               'startTime': ' '.join(fields[3:8]), 'executable': fields[8], 'role': expected['role']}
        row['identityMatches'] = all(row[k] == expected[k] for k in ('parentPid', 'startTime', 'executable'))
        if pid == 33144:
            row['daemonLogState'] = preserved_daemon_log_state()
            row['allowedIdle'] = row['identityMatches'] and 0.0 <= row['cpuPercent'] <= 0.1 and row['daemonLogState']['state'] == 'idle'
            row['allowancePolicy'] = 'exact known daemon identity, <=0.1% CPU and independently observed last Gradle log transition IDLE'
        else:
            row['allowedIdle'] = row['identityMatches'] and row['cpuPercent'] == 0.0
            row['allowancePolicy'] = 'exact known worker identity and instantaneous 0.0% CPU'
        found[pid] = row
    return [found.get(pid, {'pid': pid, 'present': False, 'allowedIdle': False, 'role': expected['role']})
            for pid, expected in PRESERVED_IDLE_PROCESSES.items()]


def external_work():
    # Command lines are used only for fixed marker classification, never persisted/printed.
    output = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True)
    preserved = preserved_process_snapshot()
    by_pid = {r['pid']: r for r in preserved}
    blockers = []
    docker_backend = False
    for line in output.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) != 2 or int(fields[0]) == os.getpid():
            continue
        pid, command = int(fields[0]), fields[1]
        if 'com.docker.backend' in command:
            docker_backend = True
        if pid in by_pid:
            snapshot = by_pid[pid]
            if snapshot.get('allowedIdle') and snapshot['role'] in command:
                continue
            blockers.append({'pid': pid, 'marker': 'preserved pre-task process became active or identity changed',
                             'safeSnapshot': snapshot})
            continue
        for marker in EXTERNAL_MARKERS:
            if marker in command:
                blockers.append({'pid': pid, 'marker': marker})
                break
    docker = shutil.which('docker')
    if docker:
        try:
            result = subprocess.run([docker, 'ps', '--format', '{{.ID}}'], capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                blockers.extend({'containerId': value, 'marker': 'running Docker container'} for value in result.stdout.split())
            elif docker_backend:
                blockers.append({'marker': 'Docker backend alive but containers could not be inspected'})
        except subprocess.TimeoutExpired:
            blockers.append({'marker': 'Docker live container inspection timed out'})
    elif docker_backend:
        blockers.append({'marker': 'Docker backend alive but Docker CLI unavailable'})
    observation = {'recordedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'preservedPreTaskProcesses': preserved, 'blockers': blockers,
                   'policy': 'exact identities; daemon <=0.1% CPU plus independent log IDLE, worker 0.0% CPU; no process terminated'}
    with COORDINATION_LOG.open('a') as stream:
        stream.write(json.dumps(observation) + '\n')
    return blockers


def machine_snapshot():
    commands = {
        'pmsetBattery': ['pmset', '-g', 'batt'],
        'pmsetCustom': ['pmset', '-g', 'custom'],
        'cpuModel': ['sysctl', '-n', 'machdep.cpu.brand_string'],
        'cpuCores': ['sysctl', '-n', 'hw.ncpu', 'hw.physicalcpu', 'hw.logicalcpu'],
    }
    result = {'recorded': utc(), 'loadAverage': list(os.getloadavg()),
              'preservedPreTaskProcesses': preserved_process_snapshot()}
    for key, argv in commands.items():
        result[key] = subprocess.check_output(argv, text=True, stderr=subprocess.STDOUT).strip()
    match = re.search(r"Now drawing from '([^']+)'", result['pmsetBattery'])
    if match is None:
        raise ValueError('Cannot establish actual pmset power source; refuse measurement')
    result['powerSignature'] = {
        'powerSource': match.group(1), 'powerSettings': result['pmsetCustom'],
        'cpuModel': result['cpuModel'], 'cpuCores': result['cpuCores'],
    }
    return result


MEASURED_MODULES = ('plugin', 'analysis-model', 'analysis-core', 'editor-ui', 'analysis-runtime')


def measured_source_files(cwd):
    """Bounded source/build inventory, including fixture sources/resources, never build outputs."""
    root = Path(cwd)
    files = set(root.glob('*.gradle.kts')) | set(root.glob('gradle.properties'))
    files.update(p for p in (root / 'gradle').glob('*') if p.is_file())
    for module in MEASURED_MODULES:
        source = root / module / 'src'
        if source.exists():
            files.update(p for p in source.rglob('*') if p.is_file())
        files.update((root / module).glob('*.gradle.kts'))
    return sorted(files)


def measured_tree_path(relative):
    parts = Path(relative).parts
    if len(parts) == 1:
        return relative.endswith('.gradle.kts') or relative == 'gradle.properties'
    if parts[0] == 'gradle':
        return len(parts) == 2
    if parts[0] in MEASURED_MODULES:
        return (len(parts) >= 3 and parts[1] == 'src') or (len(parts) == 2 and parts[1].endswith('.gradle.kts'))
    return False


def source_fingerprint(cwd):
    root = Path(cwd)
    hasher = hashlib.sha256()
    for path in measured_source_files(root):
        hasher.update(str(path.relative_to(root)).encode())
        hasher.update(b'\0')
        hasher.update(path.read_bytes())
        hasher.update(b'\0')
    return hasher.hexdigest()


def git_source_identity(cwd, git_repository=None, frozen_revision=None):
    root = Path(cwd)
    if frozen_revision is None:
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
        status = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=root, text=True)
        return {'head': head, 'dirty': bool(status.strip()), 'kind': 'git worktree',
                'scope': 'exact git HEAD and tracked dirty state; measured-source fingerprint also covers untracked measured sources/resources'}
    if git_repository is None:
        raise ValueError('Frozen archive verification requires the candidate Git repository')
    tree = subprocess.check_output(['git', 'ls-tree', '-r', '-z', frozen_revision], cwd=git_repository)
    expected = {}
    for entry in tree.split(b'\0'):
        if not entry:
            continue
        attributes, relative_bytes = entry.split(b'\t', 1)
        relative = relative_bytes.decode()
        mode, object_type, blob = attributes.decode().split()
        if object_type == 'blob' and measured_tree_path(relative):
            expected[relative] = {'mode': mode, 'blob': blob}
    measured = {str(path.relative_to(root)): path for path in measured_source_files(root)}
    if set(measured) != set(expected):
        raise ValueError(f'Frozen archive inventory differs: missing={sorted(set(expected) - set(measured))}, extra={sorted(set(measured) - set(expected))}')
    for relative, expected_blob in expected.items():
        path = measured[relative]
        data = os.readlink(path).encode() if expected_blob['mode'] == '120000' else path.read_bytes()
        algorithm = hashlib.sha1 if len(expected_blob['blob']) == 40 else hashlib.sha256
        actual_blob = algorithm(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if actual_blob != expected_blob['blob']:
            raise ValueError(f'Frozen archive measured file differs from {frozen_revision}: {relative}')
    fingerprint = source_fingerprint(root)
    return {'head': frozen_revision, 'dirty': False, 'kind': 'verified git archive',
            'archiveIdentity': fingerprint, 'verifiedMeasuredFileCount': len(expected),
            'fixtureFilesIncluded': sum('/src/test/' in path or '/src/visualTest/' in path for path in expected),
            'identityMethod': 'bounded measured source/build and fixture inventory matched frozen commit ls-tree Git blob hashes; direct archive file bytes verified',
            'scope': 'no .git required; excludes build outputs and unrelated archive files; same inventory as source_fingerprint'}


def jobs_from_plan(plan):
    templates = {'analysis': plan['primaryAnalysis']['commands']}
    templates.update({w['name']: w['commands'] for w in plan['workloads']})
    jobs = []
    for workload_index, workload in enumerate(GATES):
        for pair in range(1, 4):
            order = ('candidate', 'baseline') if (pair + workload_index) % 2 else ('baseline', 'candidate')
            for side in order:
                template = next(c for c in templates[workload] if c['side'] == side)
                job = dict(template, argv=list(template['argv']), freshJvm=pair,
                           workload=workload, key=f'{workload}-{side}-{pair}')
                for option in ('--no-daemon', '--max-workers=2', '--info'):
                    if option not in job['argv']:
                        job['argv'] = [job['argv'][0], option, *job['argv'][1:]]
                jobs.append(job)
    return jobs


def check_source_arguments(job):
    test, gate, output = GATES[job['workload']]
    files = list((Path(job['cwd']) / 'plugin/src/test').rglob(test + '.kt'))
    if len(files) != 1:
        raise ValueError(f"{job['key']}: missing/ambiguous harness {test}")
    source = files[0].read_text()
    if gate not in source or output not in source:
        raise ValueError(f"{job['key']}: gate/output not recognized by actual source")
    props = dict(a[2:].split('=', 1) for a in job['argv'] if a.startswith('-D') and '=' in a)
    for key in props:
        if key != 'issue93.measure.revision' and key not in source:
            raise ValueError(f"{job['key']}: argument {key} not read by harness")
    if props.get(gate) != 'true' or f'*.{test}' not in job['argv']:
        raise ValueError(f"{job['key']}: measurement gate or test filter is wrong")
    return {'harness': str(files[0]), 'sha256': digest(files[0]), 'properties': props}


def raw_evidence(path):
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    envs = [r for r in rows if r.get('kind') == 'environment']
    kinds = sorted({r.get('kind') for r in rows})
    if len(envs) != 1 or not any(r.get('kind') in ('sample', 'write-wait-sample', 'payload') for r in rows):
        raise ValueError(f'{path}: missing single environment or raw measurements')
    return {'sha256': digest(path), 'rows': len(rows), 'kinds': kinds, 'environment': envs[0]}


def verified_jvm(log):
    prefix = 'ISSUE97_VERIFIED_TEST_JVM '
    lines = log.read_text(errors='replace').splitlines()
    metadata = [json.loads(line.split(prefix, 1)[1]) for line in lines if prefix in line]
    launches = [line for line in lines if "Starting process 'Gradle Test Executor" in line and 'Command:' in line]
    if len(metadata) != 1 or len(launches) != 1:
        raise ValueError(f'{log}: actual Test task metadata or --info Test Executor launch missing')
    value = metadata[0]
    if value['omittedSensitiveOptionCount']:
        raise ValueError('Sensitive JVM options were omitted; inspect environment before comparison')
    if any(value['javaLauncherExecutable'] not in line for line in launches):
        raise ValueError('Recorded launcher does not match actual Test Executor command')
    value['launchCommandLines'] = launches
    value['actualHeapAndVmFlags'] = sorted(set(re.findall(r'(?:^|\s)(-Xm\S+|-XX:\S+)', launches[0])))
    value['logSha256'] = digest(log)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--candidate-head', required=True, help='Explicit full 40-hex frozen tracked-clean candidate SHA')
    parser.add_argument('--only', choices=list(GATES), action='append')
    parser.add_argument('--retry-failed', action='store_true', help='Retry failed/dead interrupted attempts with NEW immutable paths')
    args = parser.parse_args()
    if re.fullmatch(r'[0-9a-f]{40}', args.candidate_head) is None:
        parser.error('--candidate-head must be a full lowercase 40-hex SHA')
    plan_path = ROOT / 'performance-plan.json'
    plan = json.loads(plan_path.read_text())
    jobs = jobs_from_plan(plan)
    if args.only:
        jobs = [j for j in jobs if j['workload'] in args.only]
    for job in jobs:
        job['sourceValidation'] = check_source_arguments(job)
    if not args.execute:
        print(json.dumps({'execution': False, 'count': len(jobs), 'jobs': jobs}, indent=2))
        return
    sensitive_environment()
    state_path = ROOT / 'performance-run-state.json'
    with (ROOT.parent / 'performance-run.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit('Another performance runner owns the lock; no workload started.')
        state = json.loads(state_path.read_text()) if state_path.exists() else {
            'planSha256': digest(plan_path), 'created': utc(), 'commands': {}, 'candidateRequiredHead': args.candidate_head}
        if state['planSha256'] != digest(plan_path):
            raise SystemExit('Plan changed since execution began; no workload started.')
        for records in state['commands'].values():
            for record in records:
                if record['status'] in ('running', 'starting') and record.get('pid') and alive(record['pid']):
                    raise SystemExit(f"Recorded external child {record['pid']} is alive; refuse restart.")
        blockers = external_work()
        if blockers:
            raise SystemExit('External workload is alive; no workload started:\n' + json.dumps(blockers, ensure_ascii=False))
        if state.get('candidateRequiredHead') != args.candidate_head:
            raise SystemExit('Candidate SHA changed since campaign began.')
        runner_hash = digest(Path(__file__))
        if state.get('runnerSha256', runner_hash) != runner_hash:
            raise SystemExit('Runner changed since campaign began.')
        state['runnerSha256'] = runner_hash
        init_hash = digest(Path(plan['setup']['initScript']))
        if state.get('initScriptSha256', init_hash) != init_hash:
            raise SystemExit('Measurement init script changed since runner began; refuse mixed setup.')
        state['initScriptSha256'] = init_hash
        fingerprints = {side: source_fingerprint(cwd) for side, cwd in plan['setup']['workingDirectories'].items()}
        if state.get('sourceFingerprints', fingerprints) != fingerprints:
            raise SystemExit('Measurement source/build inputs changed since runner began; refuse mixed-revision resume.')
        source_revisions = {side: git_source_identity(
            cwd, git_repository=plan['setup']['workingDirectories']['candidate'],
            frozen_revision=plan['setup']['baselineRevision'] if side == 'baseline' else None,
        ) for side, cwd in plan['setup']['workingDirectories'].items()}
        if source_revisions['baseline']['head'] != plan['setup']['baselineRevision']:
            raise SystemExit('Frozen baseline git HEAD differs from planned PR96 revision; stopped.')
        if state.get('sourceRevisions', source_revisions) != source_revisions:
            raise SystemExit('Git HEAD or dirty-state changed since runner began; refuse mixed source provenance.')
        state['sourceRevisions'] = source_revisions
        state['sourceFingerprints'] = fingerprints
        if source_revisions['candidate']['head'] != args.candidate_head or source_revisions['candidate']['dirty']:
            raise SystemExit('Candidate must have tracked-clean exact --candidate-head.')
        for side, cwd in plan['setup']['workingDirectories'].items():
            manifest = {str(p.relative_to(Path(cwd))): digest(p) for p in measured_source_files(cwd)}
            manifest_path = ROOT / f'{side}-source-manifest.json'
            if manifest_path.exists() and json.loads(manifest_path.read_text()) != manifest:
                raise SystemExit('Measured source manifest changed.')
            save(manifest_path, manifest)
        snapshot = machine_snapshot()
        if state.get('initialMachineSnapshot', snapshot)['powerSignature'] != snapshot['powerSignature']:
            raise SystemExit('Power source/settings or CPU identity changed since session began; refuse resume.')
        state.setdefault('initialMachineSnapshot', snapshot)
        state.setdefault('resumeMachineSnapshots', []).append(snapshot)
        save(state_path, state)
        for job in jobs:
            records = state['commands'].setdefault(job['key'], [])
            if records and records[-1]['status'] == 'completed':
                if digest(Path(records[-1]['raw'])) != records[-1]['rawEvidence']['sha256']:
                    raise SystemExit(f"Completed raw artifact changed: {job['key']}")
                print('skip completed', job['key'], flush=True)
                continue
            if records and not args.retry_failed:
                raise SystemExit(f"{job['key']} has a failed/interrupted attempt; inspect then use --retry-failed.")
            if source_fingerprint(job['cwd']) != state['sourceFingerprints'][job['side']]:
                raise SystemExit('Source/build input changed before next workload; stopped.')
            blockers = external_work()
            if blockers:
                raise SystemExit('External workload detected before next command:\n' + json.dumps(blockers, ensure_ascii=False))
            current_revision = git_source_identity(
                job['cwd'], git_repository=plan['setup']['workingDirectories']['candidate'],
                frozen_revision=plan['setup']['baselineRevision'] if job['side'] == 'baseline' else None,
            )
            if current_revision != state['sourceRevisions'][job['side']]:
                raise SystemExit('Git HEAD/dirty-state changed before next workload; stopped.')
            before_snapshot = machine_snapshot()
            if before_snapshot['powerSignature'] != state['initialMachineSnapshot']['powerSignature']:
                raise SystemExit('Power source/settings or CPU identity changed before measurement; stopped.')
            attempt = ROOT / 'performance-attempts' / job['key'] / uuid.uuid4().hex
            attempt.mkdir(parents=True, exist_ok=False)
            raw = attempt / 'raw.jsonl'
            log = attempt / 'gradle.log'
            output_key = GATES[job['workload']][2]
            argv = [f'-D{output_key}={raw}' if a.startswith(f'-D{output_key}=') else a for a in job['argv']]
            revision_arg = '-Dissue93.measure.revision=' + current_revision['head']
            argv = [revision_arg if a.startswith('-Dissue93.measure.revision=') else a for a in argv]
            if not any(a.startswith('-Dissue93.measure.revision=') for a in argv):
                argv.append(revision_arg)
            record = {'status': 'starting', 'started': utc(), 'cwd': job['cwd'], 'argv': argv,
                      'raw': str(raw), 'log': str(log), 'sourceValidation': job['sourceValidation'],
                      'sourceFingerprint': state['sourceFingerprints'][job['side']],
                      'sourceRevision': current_revision,
                      'baselineFrozenRevision': plan['setup']['baselineRevision'],
                      'machineBefore': before_snapshot,
                      'launcherEnvironment': {'platform': platform.platform(), 'python': sys.version,
                                              'JAVA_HOME': os.environ.get('JAVA_HOME'),
                                              'scope': 'runner/launcher environment; actual test JVM fields are in rawEvidence.environment'}}
            records.append(record)
            save(state_path, state)
            start = time.monotonic()
            print('start', job['key'], str(log), flush=True)
            child = None
            try:
                with log.open('x') as stream:
                    child = subprocess.Popen(argv, cwd=job['cwd'], stdout=stream, stderr=subprocess.STDOUT,
                                             start_new_session=True)
                    record.update(status='running', pid=child.pid, processGroup=child.pid)
                    save(state_path, state)
                    returncode = child.wait()
                record['returnCode'] = returncode
                if returncode:
                    raise RuntimeError(f'Gradle exited {returncode}')
                record['rawEvidence'] = raw_evidence(raw)
                record['verifiedJVM'] = verified_jvm(log)
                normalized = []
                for argument in record['verifiedJVM']['allJvmArgs']:
                    if argument.startswith('-Dissue93.measure.revision=') or (argument.startswith('-Dissue93.measure') and '.output=' in argument):
                        continue
                    for checkout in (str(Path(job['cwd']).resolve()), job['cwd']):
                        argument = argument.replace(checkout, '<CHECKOUT>')
                    normalized.append(argument)
                record['verifiedJVM']['normalizedJvmArgs'] = sorted(normalized)
                env = record['rawEvidence']['environment']
                if revision_arg not in record['verifiedJVM']['allJvmArgs']:
                    raise RuntimeError('Actual fixture JVM revision property does not match frozen source revision')
                if env.get('revision') is not None and env['revision'] != current_revision['head']:
                    raise RuntimeError('Raw fixture revision does not match frozen source revision')
                record['revisionEvidence'] = 'actual JVM property plus verified source SHA/fingerprint; raw header checked when present'
                same_workload = [r for key, rs in state['commands'].items() if key.startswith(job['workload'] + '-')
                                 for r in rs if r.get('status') == 'completed']
                for previous in same_workload:
                    if previous['rawEvidence']['environment'].get('runId') == env.get('runId'):
                        raise RuntimeError('Fixture runId reused; fresh JVM evidence invalid')
                    vm = previous['verifiedJVM']
                    for key in ('javaLauncherExecutable', 'javaVersion', 'javaVendor', 'actualHeapAndVmFlags', 'normalizedJvmArgs'):
                        if vm.get(key) != record['verifiedJVM'].get(key):
                            raise RuntimeError('Actual JVM setup changed: ' + key)
                    for key in ('javaVersion', 'javaVm', 'os', 'arch', 'ideBuild', 'processors', 'maxHeapBytes'):
                        if previous['rawEvidence']['environment'].get(key) != env.get(key):
                            raise RuntimeError('Fixture environment changed: ' + key)
                record['machineAfter'] = machine_snapshot()
                after_blockers = external_work()
                record['externalWorkAfter'] = after_blockers
                if after_blockers:
                    raise RuntimeError('External workload or preserved-process activity detected after measurement')
                if record['machineAfter']['powerSignature'] != record['machineBefore']['powerSignature']:
                    raise RuntimeError('Power source/settings or CPU identity changed during measurement; evidence not comparable')
                record['powerWarnings'] = []
                if record['machineAfter']['pmsetBattery'] != record['machineBefore']['pmsetBattery']:
                    record['powerWarnings'].append('Battery charge/status report varied while power source/settings remained stable; inspect raw snapshots.')
                record['status'] = 'completed'
            except BaseException as error:
                if child is not None and child.poll() is None:
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait()
                record.update(status='failed', error=str(error))
                raise
            finally:
                record.update(finished=utc(), elapsedSeconds=time.monotonic() - start)
                save(state_path, state)
            print('completed', job['key'], flush=True)
        print('Selected workloads complete; raw paths and environments are recorded in', state_path)


if __name__ == '__main__':
    main()
