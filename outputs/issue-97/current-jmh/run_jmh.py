"""Prepare and measure the existing seven JMH jobs serially; never run concurrently.

Raw step artifacts are immutable. Resume skips completed successful steps and
refuses to duplicate a still-running child. Smoke checks coverage only; summary
uses full-profile baseline/candidate results and preserves JMH error intervals.
"""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import math
import os
import re
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

JOBS = ["sort-pair-events", "sort-random", "sort-ascending", "sort-descending", "cancellation", "pairing", "preferences"]
JAVA = Path("/Users/sijun-yang/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home/bin/java")
HEAP = ["-Xms2g", "-Xmx2g"]
MEASUREMENT_LIMIT_SECONDS = 240
JDK_VERSION = "17.0.17"
FROZEN_BASELINE = "84d1a43141b1933c81e027ef01d1579fc4679e6e"
TASK_EVIDENCE_ROOT = Path(__file__).resolve().parent.parent
COORDINATION_LOG = Path(__file__).resolve().parent / "coordination-observations.jsonl"
SENSITIVE_JVM_ENVIRONMENT = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JMH_JVM_ARGS", "JAVA_OPTS", "GRADLE_OPTS")
EXTERNAL_MARKERS = (
    "org.gradle.wrapper.GradleWrapperMain", "Gradle Test Executor", "GradleDaemon", "GradleWorkerMain",
    "org.openjdk.jmh.Main", "benchmarks.jar", "org.jetbrains.intellij.platform.gradle.artifacts.transform",
    "com.intellij.idea.Main", "com.intellij.idea.ApplicationLoader", "com.jetbrains.pluginverifier",
    "plugin-verifier", "PluginVerifierMain", "ide-starter", "starter-squashed", "driver-client",
    "qodana scan", "docker run", "docker exec",
)


def now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".next")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def sensitive_environment():
    # Never persist or print values; options can contain credentials or agents.
    present = [name for name in SENSITIVE_JVM_ENVIRONMENT if os.environ.get(name)]
    if present:
        raise ValueError(f"Inherited JVM option variables are set; refuse measurement: {present}")
    return {name: "unset" for name in SENSITIVE_JVM_ENVIRONMENT}


# Exact processes observed before this task; never terminate them.
# PID, parent, executable, and start time must match and reported CPU must be 0.0%.
PRESERVED_IDLE_PROCESSES = {
    33144: {'parentPid': 1, 'startTime': 'Wed Oct 7 12:26:09 2026',
            'executable': '/Users/sijun-yang/.sdkman/candidates/java/25.0.3-amzn/bin/java', 'role': 'GradleDaemon'},
    33413: {'parentPid': 33144, 'startTime': 'Wed Oct 7 12:27:10 2026',
            'executable': '/Users/sijun-yang/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.17+10/Contents/Home/bin/java', 'role': 'GradleWorkerMain'},
}


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
        row['allowedIdle'] = row['identityMatches'] and row['cpuPercent'] == 0.0
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
    observation = {'recordedUtc': now(),
                   'preservedPreTaskProcesses': preserved, 'blockers': blockers,
                   'policy': 'exact identity and instantaneous 0.0% CPU allowance; no process terminated'}
    with COORDINATION_LOG.open('a') as stream:
        stream.write(json.dumps(observation) + '\n')
    return blockers


def machine_snapshot():
    commands = {
        "pmsetBattery": ["pmset", "-g", "batt"],
        "pmsetCustom": ["pmset", "-g", "custom"],
        "pmsetThermal": ["pmset", "-g", "therm"],
        "cpuModel": ["sysctl", "-n", "machdep.cpu.brand_string"],
        "cpuCores": ["sysctl", "-n", "hw.ncpu", "hw.physicalcpu", "hw.logicalcpu"],
    }
    snapshot = {"recordedUtc": now(), "loadAverage": list(os.getloadavg()),
                "preservedPreTaskProcesses": preserved_process_snapshot()}
    for key, command in commands.items():
        snapshot[key] = subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=10).strip()
    match = re.search(r"Now drawing from '([^']+)'", snapshot["pmsetBattery"])
    if match is None:
        raise ValueError("Cannot establish actual power source; refuse measurement")
    snapshot["powerSignature"] = {
        "powerSource": match.group(1), "powerSettings": snapshot["pmsetCustom"],
        "cpuModel": snapshot["cpuModel"], "cpuCores": snapshot["cpuCores"],
        "thermalReport": snapshot["pmsetThermal"],
    }
    return snapshot


def verify_fork(row):
    if Path(row.get("jvm", "")).resolve() != JAVA.resolve():
        raise ValueError("JMH fork executable differs from the fixed JDK17 launcher")
    if str(row.get("jdkVersion", "")) != JDK_VERSION:
        raise ValueError("JMH fork does not report the fixed JDK17 patch version")
    arguments = row.get("jvmArgs", [])
    for option, expected in (("-Xms", "2g"), ("-Xmx", "2g")):
        values = [argument[len(option):].lower() for argument in arguments if argument.startswith(option)]
        if not values or any(value != expected for value in values):
            raise ValueError(f"JMH fork contains absent/conflicting {option} settings")
    if any(argument.startswith(("-XX:InitialHeapSize=", "-XX:MaxHeapSize=", "-XX:InitialRAMPercentage=", "-XX:MaxRAMPercentage=")) for argument in arguments):
        raise ValueError("JMH fork contains alternative heap-sizing overrides")
    return {"jvm": row["jvm"], "jdkVersion": row["jdkVersion"], "jvmArgs": arguments, "fixedHeapVerified": True}


def measured_path(relative):
    path = Path(relative)
    owners = {"analysis-model", "analysis-core", "editor-ui", "analysis-runtime", "plugin", "benchmarks"}
    if any(part in {"build", "outputs", ".gradle", ".git", ".intellijPlatform", ".kotlin"} for part in path.parts):
        return False
    if relative in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}:
        return True
    if path.parts[0] in {"gradle", "licenses"}:
        return True
    if path.parts[0] in owners:
        return (relative.endswith("build.gradle.kts") or "/src/main/" in relative or "/src/jmh/" in relative
                or path.parts[0] == "benchmarks" and path.suffix in {".py", ".sh"})
    return False


def source_inventory(root):
    # Traverse only source/build-definition roots, never generated build/output trees.
    candidates = set()
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"):
        file = root / name
        if file.is_file():
            candidates.add(file)
    for name in ("gradle", "licenses"):
        directory = root / name
        if directory.is_dir():
            candidates.update(file for file in directory.rglob("*") if file.is_file())
    for owner in ("analysis-model", "analysis-core", "editor-ui", "analysis-runtime", "plugin", "benchmarks"):
        directory = root / owner
        if not directory.is_dir():
            continue
        file = directory / "build.gradle.kts"
        if file.is_file():
            candidates.add(file)
        for sources in (directory / "src/main", directory / "src/jmh"):
            if sources.is_dir():
                candidates.update(file for file in sources.rglob("*") if file.is_file())
        if owner == "benchmarks":
            candidates.update(directory.glob("*.py"))
            helper = directory / "bencher"
            if helper.is_dir():
                candidates.update(file for file in helper.rglob("*") if file.is_file() and file.suffix in {".py", ".sh"})
    return {file.relative_to(root).as_posix(): digest(file) for file in sorted(candidates)
            if measured_path(file.relative_to(root).as_posix())}


def repository_info(root, git_repository=None):
    measured = source_inventory(root)
    if (root / ".git").exists():
        def git(*arguments):
            return subprocess.check_output(["git", *arguments], cwd=root, text=True).strip()
        return {"root": str(root), "head": git("rev-parse", "HEAD"), "status": git("status", "--porcelain"),
                "identityMethod": "git working tree plus measured source bytes", "inputFileHashes": measured}
    if git_repository is None:
        raise ValueError("An archive baseline requires a Git repository to verify the frozen commit blobs")
    raw_tree = subprocess.check_output(["git", "ls-tree", "-r", "-z", FROZEN_BASELINE], cwd=git_repository)
    expected = {}
    for entry in raw_tree.split(b"\0"):
        if not entry:
            continue
        metadata, relative = entry.split(b"\t", 1)
        _, kind, blob = metadata.decode().split()
        relative = relative.decode()
        if kind == "blob" and measured_path(relative):
            expected[relative] = blob
    if set(expected) != set(measured):
        raise ValueError(f"Frozen archive measured files differ: missing={sorted(set(expected) - set(measured))}, extra={sorted(set(measured) - set(expected))}")
    checked = {}
    for relative, blob in expected.items():
        file = root / relative
        data = os.readlink(file).encode() if file.is_symlink() else file.read_bytes()
        actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if actual != blob:
            raise ValueError(f"Frozen archive file differs from {FROZEN_BASELINE}: {relative}")
        checked[relative] = blob
    return {"root": str(root), "head": FROZEN_BASELINE, "status": "git archive (no .git)",
            "identityMethod": "measured baseline bytes matched frozen commit ls-tree blob identities",
            "verifiedGitBlobHashes": checked, "inputFileHashes": measured}


class Runner:
    def __init__(self, output, resume):
        self.output = output
        # Share the same task-wide lock with run_performance.py, even across
        # different JMH evidence directories.
        self.global_lock = (TASK_EVIDENCE_ROOT / "performance-run.lock").open("a+")
        try:
            fcntl.flock(self.global_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another task performance runner owns the shared lock")
        sensitive_environment()
        blockers = external_work()
        if blockers:
            raise RuntimeError(f"External measurement/build/IDE/container workload is alive: {blockers}")
        output.mkdir(parents=True, exist_ok=True)
        self.lock = (output / "runner.lock").open("a+")
        try:
            fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("A JMH runner already owns this evidence directory")
        self.state_path = output / "state.json"
        if self.state_path.exists():
            self.state = json.loads(self.state_path.read_text())
            if not resume:
                raise RuntimeError("Evidence already exists; use --resume to skip completed steps")
            child = self.state.get("activeChildPid")
            if alive(child):
                raise RuntimeError(f"Prior child PID {child} is still alive; refusing duplicate execution")
            if self.state.get("status") == "completed":
                raise RuntimeError("This evidence run is already completed")
        else:
            self.state = {"createdUtc": now(), "steps": {}, "status": "preparing"}
        snapshot = machine_snapshot()
        initial = self.state.setdefault("initialMachineSnapshot", snapshot)
        if initial["powerSignature"] != snapshot["powerSignature"]:
            raise RuntimeError("Power source/settings or CPU identity changed; refuse resume")
        self.state.setdefault("resumeMachineSnapshots", []).append(snapshot)
        self.state.update({"runnerPid": os.getpid(), "activeChildPid": None, "updatedUtc": now()})
        self.save()

    def save(self):
        self.state["updatedUtc"] = now()
        write_json(self.state_path, self.state)

    def run(self, name, command, cwd, timeout, bundle=None):
        previous = self.state["steps"].get(name)
        if previous:
            if previous["status"] == "completed" and previous["exitCode"] == 0:
                retained = Path(previous["directory"])
                for filename, expected in previous.get("artifactSha256", {}).items():
                    if digest(retained / filename) != expected:
                        raise RuntimeError(f"Immutable completed artifact changed: {retained / filename}")
                return retained
            raise RuntimeError(f"Step {name} has immutable incomplete/failed evidence; start a fresh output run to retry")
        sensitive_environment()
        blockers = external_work()
        if blockers:
            raise RuntimeError(f"External workload detected before {name}: {blockers}")
        before_snapshot = machine_snapshot()
        if before_snapshot["powerSignature"] != self.state["initialMachineSnapshot"]["powerSignature"]:
            raise RuntimeError(f"Power source/settings or CPU identity changed before {name}")
        directory = self.output / "steps" / name
        directory.mkdir(parents=True, exist_ok=False)
        if bundle is not None:
            for filename in ("benchmarks.jar", "run.args", "bundle.json"):
                shutil.copyfile(bundle / filename, directory / filename)
        record = {"name": name, "command": list(map(str, command)), "cwd": str(cwd), "directory": str(directory), "startedUtc": now(), "status": "alive", "timeoutSeconds": timeout,
                  "machineBefore": before_snapshot, "sensitiveJvmEnvironment": sensitive_environment()}
        write_json(directory / "command.json", record)
        self.state["steps"][name] = record
        self.state["status"] = "alive"
        self.save()
        print(f"Running {name}; raw log: {directory / 'stdout.log'}", flush=True)
        started = time.monotonic()
        try:
            with (directory / "stdout.log").open("x") as stream:
                process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
                self.state["activeChildPid"] = process.pid
                record["childPid"] = process.pid
                self.save()
                try:
                    code = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                    code = -124
        except BaseException:
            # Keep an existing child PID so resume cannot silently duplicate it.
            self.state["status"] = "interrupted"
            self.save()
            raise
        elapsed = time.monotonic() - started
        after_snapshot = None
        blockers_after = []
        condition_error = None
        try:
            after_snapshot = machine_snapshot()
            blockers_after = external_work()
        except Exception as error:
            # Preserve exit/time/raw evidence even when post-stage inspection
            # fails. Persist the type only, avoiding arbitrary command stderr.
            condition_error = type(error).__name__
        conditions_passed = (after_snapshot is not None and condition_error is None
                             and before_snapshot["powerSignature"] == after_snapshot["powerSignature"]
                             and not blockers_after)
        record.update({"machineAfter": after_snapshot, "externalWorkAfter": blockers_after, "conditionsPassed": conditions_passed, "conditionInspectionErrorType": condition_error,
                       "powerWarnings": ["Battery charge/status changed; source/settings stable"] if after_snapshot is not None and before_snapshot["pmsetBattery"] != after_snapshot["pmsetBattery"] else [],
                       "thermalReportChanged": after_snapshot is not None and before_snapshot["pmsetThermal"] != after_snapshot["pmsetThermal"]})
        measurement = name.startswith("measure-")
        passes_duration = code == 0 and elapsed <= MEASUREMENT_LIMIT_SECONDS if measurement else None
        record.update({"exitCode": code, "elapsedSeconds": elapsed, "endedUtc": now(),
                       "measurementLimitSeconds": MEASUREMENT_LIMIT_SECONDS if measurement else None,
                       "passesDurationLimit": passes_duration,
                       "status": "completed" if code == 0 and passes_duration is not False and conditions_passed else "failed"})
        record["artifactSha256"] = {file.name: digest(file) for file in directory.iterdir() if file.is_file()}
        write_json(directory / "status.json", record)
        for file in directory.iterdir():
            if file.is_file():
                file.chmod(0o444)
        self.state["activeChildPid"] = None
        self.state["status"] = "preparing"
        self.save()
        if code:
            raise RuntimeError(f"{name} failed with exit code {code}; raw evidence is retained")
        if not conditions_passed:
            raise RuntimeError(f"Power/CPU conditions changed or external work overlapped {name}; raw evidence is retained")
        if passes_duration is False:
            raise RuntimeError(f"{name} exceeded the existing {MEASUREMENT_LIMIT_SECONDS}s execution limit; raw evidence is retained")
        return directory


def portable_args(path, smoke=False):
    arguments = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    def option(flag):
        if flag not in arguments:
            raise ValueError(f"Prepared bundle lacks required option {flag}: {path}")
        return arguments[arguments.index(flag) + 1]
    expected = {"-f": "1", "-wi": "0", "-i": "1"} if smoke else {"-f": "2", "-wi": "2", "-i": "3", "-w": "1s", "-r": "1s"}
    for flag, value in expected.items():
        if str(option(flag)) != value:
            raise ValueError(f"Prepared {flag} differs from original profile: {option(flag)} != {value}")
    if "gc" not in str(option("-prof")):
        raise ValueError("Prepared bundle lacks GC profiler")
    if "-jvm" in arguments and Path(option("-jvm")).resolve() != JAVA.resolve():
        raise ValueError("Prepared bundle overrides the fixed JDK17 fork executable")
    return arguments


def prepare(runner, root, side, job, smoke=False):
    name = f"prepare-{side}-{job}"
    command = [str(root / "gradlew"), "--no-daemon", "--no-configuration-cache", ":benchmarks:prepareBenchmarkJob", "--console=plain"]
    if job != "all-smoke":
        command.append(f"-PbenchmarkJob={job}")
    if smoke:
        command.append("-PbenchmarkSmoke=true")
    step = runner.run(name, command, root, 1800)
    bundle = runner.output / "bundles" / side / job
    if not bundle.exists():
        source = root / "benchmarks/build/benchmark-jobs" / ("all" if smoke else job)
        bundle.mkdir(parents=True, exist_ok=False)
        for file in ("benchmarks.jar", "run.args"):
            shutil.copyfile(source / file, bundle / file)
        arguments = portable_args(bundle / "run.args", smoke)
        write_json(bundle / "bundle.json", {"side": side, "job": job, "source": str(source), "smoke": smoke,
            "jarSha256": digest(bundle / "benchmarks.jar"), "argumentsSha256": digest(bundle / "run.args"), "arguments": arguments})
        for file in bundle.iterdir():
            if file.is_file():
                file.chmod(0o444)
    else:
        portable_args(bundle / "run.args", smoke)
        metadata = json.loads((bundle / "bundle.json").read_text())
        if digest(bundle / "benchmarks.jar") != metadata["jarSha256"] or digest(bundle / "run.args") != metadata["argumentsSha256"]:
            raise RuntimeError(f"Immutable benchmark bundle changed: {bundle}")
    return bundle


def measure(runner, side, job, bundle):
    name = f"measure-{side}-{job}"
    # JMH gets the same fixed heap explicitly in its forked JVMs as the launcher.
    command = [str(JAVA), *HEAP, "@run.args", "-jvm", str(JAVA), "-jvmArgsAppend", " ".join(HEAP)]
    directory = runner.output / "steps" / name
    directory = runner.run(name, command, directory, MEASUREMENT_LIMIT_SECONDS, bundle=bundle)
    results = directory / "results.json"
    if not results.is_file():
        raise ValueError(f"No JMH JSON results from {name}")
    rows = json.loads(results.read_text())
    if not rows:
        raise ValueError(f"Empty JMH JSON results from {name}")
    for row in rows:
        if job != "all-smoke":
            if (row["forks"], row["warmupIterations"], row["measurementIterations"]) != (2, 2, 3):
                raise ValueError(f"Wrong full profile in {name}")
            if row.get("warmupTime") != "1 s" or row.get("measurementTime") != "1 s":
                raise ValueError(f"Wrong iteration durations in {name}")
        verify_fork(row)
        if "gc.alloc.rate.norm" not in row.get("secondaryMetrics", {}):
            raise ValueError(f"Missing allocation profile in {name}")
    return results


def identity(row):
    return row["benchmark"], tuple(sorted(row.get("params", {}).items()))


def finite(value):
    return value if isinstance(value, (int, float)) and math.isfinite(value) else None


def metric(row):
    return {"score": finite(row["score"]), "scoreError": finite(row.get("scoreError")),
            "scoreConfidence": [finite(value) for value in row.get("scoreConfidence", [])], "unit": row["scoreUnit"]}


def comparison(before, after):
    baseline, candidate = metric(before), metric(after)
    if baseline["unit"] != candidate["unit"]:
        raise ValueError("Before/after metric units differ")
    delta = candidate["score"] - baseline["score"] if None not in (baseline["score"], candidate["score"]) else None
    percent = 100 * delta / baseline["score"] if delta is not None and baseline["score"] else None
    interval = None
    b, c = baseline["scoreConfidence"], candidate["scoreConfidence"]
    if len(b) == len(c) == 2 and None not in b + c:
        interval = [c[0] - b[1], c[1] - b[0]]
    return {"baseline": baseline, "candidate": candidate, "delta": delta, "percentChange": percent,
            "conservativeDeltaInterval": interval, "directionUnresolvedByReportedIntervals": interval[0] <= 0 <= interval[1] if interval else None}


def summarize(runner):
    rows = []
    durations = {side: {} for side in ("baseline", "candidate")}
    for side in durations:
        for job in JOBS + (["all-smoke"] if side == "candidate" else []):
            status = runner.state["steps"][f"measure-{side}-{job}"]
            durations[side][job] = {"elapsedSeconds": status["elapsedSeconds"], "limitSeconds": MEASUREMENT_LIMIT_SECONDS,
                                    "passes": status["exitCode"] == 0 and status["elapsedSeconds"] <= MEASUREMENT_LIMIT_SECONDS}
    duration_passed = all(value["passes"] for jobs in durations.values() for value in jobs.values())
    for job in JOBS:
        before = json.loads((runner.output / "steps" / f"measure-baseline-{job}" / "results.json").read_text())
        after = json.loads((runner.output / "steps" / f"measure-candidate-{job}" / "results.json").read_text())
        old, new = {identity(row): row for row in before}, {identity(row): row for row in after}
        if old.keys() != new.keys():
            raise ValueError(f"Before/after case mismatch in {job}")
        for case in sorted(old):
            baseline, candidate = old[case], new[case]
            allocations = {name: comparison(baseline["secondaryMetrics"][name], candidate["secondaryMetrics"][name])
                           for name in ("gc.alloc.rate.norm", "gc.alloc.rate") if name in baseline["secondaryMetrics"] and name in candidate["secondaryMetrics"]}
            rows.append({"job": job, "benchmark": case[0], "params": dict(case[1]),
                         "latency": comparison(baseline["primaryMetric"], candidate["primaryMetric"]), "allocation": allocations})
    write_json(runner.output / "summary.json", {"scope": "Full-profile JMH comparison; smoke excluded from performance evidence.",
        "limitations": "Reported JMH confidence/error intervals retained per case. One suite per revision is not repeated independent suite evidence; no new machine timing threshold or pooled aggregate.",
        "executionDurationGate": {"source": ".github/workflows/benchmark-jobs.yml Java execution limit", "limitSeconds": MEASUREMENT_LIMIT_SECONDS,
                                  "passes": duration_passed, "jobs": durations}, "cases": rows})
    if not duration_passed:
        raise ValueError("At least one baseline/candidate job exceeded the existing four-minute execution limit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=Path("/private/tmp/bpg-97-baseline"))
    parser.add_argument("--candidate", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    runner = Runner(args.output.resolve(), args.resume)
    roots = {"baseline": args.baseline.resolve(), "candidate": args.candidate.resolve()}
    environment = {"java": str(JAVA), "jdkVersion": JDK_VERSION, "heapLauncherAndForks": HEAP, "measurementLimitSeconds": MEASUREMENT_LIMIT_SECONDS, "jobs": JOBS,
                   "sensitiveJvmEnvironment": sensitive_environment(), "runnerSha256": digest(Path(__file__)),
                   "repositories": {side: repository_info(root, roots["candidate"]) for side, root in roots.items()},
                   "javaVersionLog": str(runner.output / "steps/java-version/stdout.log")}
    metadata = runner.output / "environment.json"
    if metadata.exists():
        previous_environment = json.loads(metadata.read_text())
        # Keep dirty status as initial evidence, but generated output status is
        # not a measurement input. Revision + every relevant source/build hash is.
        comparable = json.loads(json.dumps(environment))
        for side in roots:
            previous_environment["repositories"][side].pop("status", None)
            comparable["repositories"][side].pop("status", None)
        if previous_environment != comparable:
            raise RuntimeError("Measurement inputs changed since this run; refusing resume")
    else:
        version = runner.run("java-version", [str(JAVA), "-version"], roots["candidate"], 30)
        write_json(metadata, environment)
    bundles = {}
    for job in JOBS:
        for side in ("baseline", "candidate"):
            bundles[side, job] = prepare(runner, roots[side], side, job)
    smoke_bundle = prepare(runner, roots["candidate"], "candidate", "all-smoke", True)
    for side, root in roots.items():
        current = repository_info(root, roots["candidate"])
        if current["head"] != environment["repositories"][side]["head"] or current["inputFileHashes"] != environment["repositories"][side]["inputFileHashes"]:
            raise RuntimeError(f"{side} inputs changed during preparation; measurements refused")
    smoke = measure(runner, "candidate", "all-smoke", smoke_bundle)
    smoke_rows = json.loads(smoke.read_text())
    if len(smoke_rows) != 46 or len({identity(row) for row in smoke_rows}) != 46:
        raise ValueError("Unfiltered smoke must contain exactly 46 distinct cases")
    for job in JOBS:
        for side in ("baseline", "candidate"):
            measure(runner, side, job, bundles[side, job])
    jobs_path = runner.output / "jobs.json"
    if not jobs_path.exists():
        write_json(jobs_path, JOBS)
    for side in ("baseline", "candidate"):
        coverage = runner.output / "coverage-inputs" / side
        for job in JOBS:
            destination = coverage / job / "results.json"
            if not destination.exists():
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(runner.output / "steps" / f"measure-{side}-{job}" / "results.json", destination)
        runner.run(f"coverage-{side}", [sys.executable, "-B", str(roots['candidate'] / "benchmarks/check_job_results.py"), str(smoke), str(coverage), str(jobs_path)], roots["candidate"], 30)
    for side, root in roots.items():
        current = repository_info(root, roots["candidate"])
        if current["head"] != environment["repositories"][side]["head"] or current["inputFileHashes"] != environment["repositories"][side]["inputFileHashes"]:
            raise RuntimeError(f"{side} inputs changed during measurement; raw evidence retained without success label")
    summarize(runner)
    runner.state["status"] = "completed"
    runner.state["completedUtc"] = now()
    runner.save()
    print(f"Completed JMH evidence: {runner.output}", flush=True)


if __name__ == "__main__":
    main()
