"""Run prepared benchmark jobs sequentially on Bencher and retrieve JMH artifacts."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path


REMOTE_RESULTS = "/bench/bencher-results.json"
TESTBED = "bencher-intel-v1-jdk17"
TERMINAL_STATUSES = {"processed", "failed", "canceled"}


def required(environment, name):
    value = environment.get(name, "")
    if not value:
        raise ValueError(f"Set {name} before running Bencher jobs")
    return value


def read_jobs(path):
    jobs = json.loads(path.read_text())
    if (
        not isinstance(jobs, list)
        or not jobs
        or any(not isinstance(job, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", job) for job in jobs)
        or len(jobs) != len(set(jobs))
    ):
        raise ValueError("The job list must contain unique, nonempty job names")
    return jobs


def command_for_job(job, environment, reset_start_point):
    command = [
        "bencher", "run",
        "--project", required(environment, "BENCHER_PROJECT"),
        "--image", required(environment, "BENCHER_IMAGE"),
        "--spec", "intel-v1",
        "--job-timeout", "300",
        "--env", f"BENCHMARK_JOB={job}",
        "--branch", required(environment, "BENCHER_BRANCH"),
        "--hash", required(environment, "BENCHER_HASH"),
        "--testbed", TESTBED,
        "--adapter", "java_jmh",
        "--file", REMOTE_RESULTS,
        "--format", "json",
        "--quiet",
    ]
    base_branch = environment.get("BENCHER_BASE_BRANCH", "")
    base_hash = environment.get("BENCHER_BASE_SHA", "")
    if bool(base_branch) != bool(base_hash):
        raise ValueError("Set both BENCHER_BASE_BRANCH and BENCHER_BASE_SHA for a pull request")
    if base_branch and reset_start_point:
        command.extend([
            "--start-point", base_branch,
            "--start-point-hash", base_hash,
            "--start-point-clone-thresholds",
            "--start-point-reset",
        ])
        # Resetting every suite would discard earlier suites from the branch Head.
    elif not base_branch and reset_start_point:
        command.extend([
            "--threshold-measure", "latency",
            "--threshold-test", "percentage",
            "--threshold-upper-boundary", "0.20",
            "--threshold-max-sample-size", "1",
        ])
    github_token = environment.get("GITHUB_TOKEN", "")
    if github_token:
        command.extend([
            "--github-actions", github_token,
            "--ci-id", f"benchmark-{job}",
            "--ci-public-links",
            "--ci-only-on-alert",
        ])
        if environment.get("BENCHER_PR_NUMBER"):
            command.extend(["--ci-number", environment["BENCHER_PR_NUMBER"]])
    return command


def execute(command, output, environment):
    # Do not include the command in errors: --github-actions contains a secret.
    try:
        with output.open("w") as stdout:
            return subprocess.run(
                command, stdout=stdout, env=environment, check=False, timeout=750,
            ).returncode
    except FileNotFoundError as error:
        raise RuntimeError("The Bencher CLI is not installed") from error
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(
            "The Bencher CLI timed out; the remote job may still be running. "
            "No further jobs were submitted."
        ) from error


def restore_results(job_output, destination):
    if job_output.get("status") not in TERMINAL_STATUSES:
        raise ValueError("The remote job is not terminal; no further jobs will be submitted")
    iterations = (job_output.get("output") or {}).get("results", [])
    if len(iterations) != 1:
        raise ValueError("Expected one remote job iteration")
    iteration = iterations[0]
    logs = [iteration.get(stream) or "" for stream in ("stdout", "stderr")]
    (destination / "human.txt").write_text("\n".join(log for log in logs if log))
    contents = (iteration.get("output") or {}).get(REMOTE_RESULTS)
    if not isinstance(contents, str):
        raise ValueError(f"The remote job did not return {REMOTE_RESULTS}")
    normalized = json.loads(contents)
    if not isinstance(normalized, list) or not normalized:
        raise ValueError("The remote JMH results must be a nonempty JSON array")
    for result in normalized:
        if not isinstance(result, dict):
            raise ValueError("A remote JMH result is not an object")
        original = result.pop("originalBenchmark", None)
        if not isinstance(original, str) or not original:
            raise ValueError("A remote JMH result is missing originalBenchmark")
        result["benchmark"] = original
    (destination / "results.json").write_text(json.dumps(normalized, indent=2) + "\n")
    if job_output["status"] != "processed" or iteration.get("exit_code") != 0:
        raise ValueError("The remote benchmark job did not complete successfully")


def run_jobs(jobs_path, results_directory, environment=None):
    environment = dict(os.environ if environment is None else environment)
    required(environment, "BENCHER_API_KEY")
    project = required(environment, "BENCHER_PROJECT")
    jobs = read_jobs(jobs_path)
    # Validate all metadata before submitting any remote work.
    commands = [command_for_job(job, environment, index == 0) for index, job in enumerate(jobs)]
    alerted_jobs = []
    for job, command in zip(jobs, commands):
        destination = results_directory / job
        destination.mkdir(parents=True, exist_ok=True)
        print(f"Measuring {job} on Bencher", flush=True)
        exit_code = execute(command, destination / "report.json", environment)
        try:
            report = json.loads((destination / "report.json").read_text())
            job_uuid = report["job"]
            if not isinstance(job_uuid, str) or not job_uuid:
                raise ValueError("Missing job UUID")
        except (ValueError, KeyError, TypeError) as error:
            raise RuntimeError(
                f"{job}: Bencher returned no remote job report (exit {exit_code}); "
                "no further jobs were submitted"
            ) from error
        view_exit = execute(
            ["bencher", "job", "view", project, job_uuid],
            destination / "job.json", environment,
        )
        if view_exit != 0:
            raise RuntimeError(f"{job}: could not retrieve the remote job (exit {view_exit})")
        try:
            restore_results(json.loads((destination / "job.json").read_text()), destination)
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            raise RuntimeError(f"{job}: invalid remote artifacts: {error}") from error
        if exit_code != 0:
            raise RuntimeError(f"{job}: Bencher failed (exit {exit_code}); artifacts were retained")
        alerts = report.get("alerts")
        if not isinstance(alerts, list):
            raise RuntimeError(f"{job}: Bencher report has no valid alerts list; artifacts were retained")
        if alerts:
            alerted_jobs.append(f"{job} ({len(alerts)})")
        report_uuid = report.get("uuid")
        if report_uuid:
            print(f"Report: https://bencher.dev/perf/{project}/reports/{report_uuid}", flush=True)
    # Finish the partition and retain every artifact before failing on regressions.
    # The CLI normally exits successfully even when its GitHub report check fails.
    if alerted_jobs:
        raise RuntimeError(
            f"Bencher reported performance alerts in: {', '.join(alerted_jobs)}; "
            "all job artifacts were retained"
        )


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: run_jobs.py JOBS_JSON RESULTS_DIRECTORY")
        run_jobs(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Bencher jobs failed: {error}", file=sys.stderr)
        sys.exit(1)
