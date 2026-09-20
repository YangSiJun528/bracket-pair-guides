"""Select benchmark changes and enforce the required aggregate CI result."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path


MEASURED_PREFIXES = (
    "benchmarks/src/",
    "plugin/src/main/java/com/sijunyang/bracketpairguides/analysis/pairing/core/",
    "plugin/src/main/kotlin/com/sijunyang/bracketpairguides/analysis/sorting/",
    "gradle/",
)
MEASURED_FILES = {
    "plugin/src/main/kotlin/com/sijunyang/bracketpairguides/preferences/BracketGuidePreferences.kt",
    "plugin/src/main/kotlin/com/sijunyang/bracketpairguides/preferences/StoredColorFormat.kt",
    "plugin/src/main/kotlin/com/sijunyang/bracketpairguides/settings/BracketGuidePreferenceNormalization.kt",
    "benchmarks/check_job_results.py",
    "build.gradle.kts",
    "settings.gradle.kts",
    "plugin/build.gradle.kts",
    "benchmarks/build.gradle.kts",
    "gradle.properties",
    "gradlew",
    ".github/workflows/benchmark-jobs.yml",
}


def affects_benchmarks(path):
    if path in MEASURED_FILES or path.startswith(MEASURED_PREFIXES):
        return True
    directory, _, filename = path.rpartition("/")
    if directory != "benchmarks/bencher":
        return False
    if filename.startswith("test_") and filename.endswith(".py"):
        return False
    return filename.endswith((".py", ".sh")) or filename.startswith("Dockerfile")


def changed_paths(base, head, pull_request):
    if any(not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (base, head)):
        raise ValueError("Expected full base and head commit hashes")
    comparison = f"{base}{'...' if pull_request else '..'}{head}"
    # Include both sides of a move so moving a measured file out still triggers CI.
    result = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "-z", comparison, "--"],
        check=True, capture_output=True,
    )
    return [os.fsdecode(path) for path in result.stdout.split(b"\0") if path]


def measurement_decision(event_name, event):
    if event_name == "workflow_dispatch":
        return True, "Manual run: measure every benchmark job."
    if event_name == "pull_request":
        pull_request = event["pull_request"]
        if pull_request["draft"]:
            return False, "Draft PR: measurements are deferred until ready for review."
        if any(label["name"] == "skip-ci" for label in pull_request["labels"]):
            return False, "The PR has the skip-ci label."
        base, head = pull_request["base"]["sha"], pull_request["head"]["sha"]
    elif event_name == "push":
        base, head = event["before"], event["after"]
        if base == "0" * 40:
            return True, "New branch: measure every benchmark job."
    else:
        raise ValueError(f"Unsupported workflow event: {event_name}")
    paths = changed_paths(base, head, event_name == "pull_request")
    count = sum(affects_benchmarks(path) for path in paths)
    return bool(count), f"{count} changed paths affect benchmark inputs."


def check_gate(needs):
    changes = needs["changes"]
    if changes["result"] != "success":
        raise ValueError("Benchmark change detection did not succeed")
    required = changes["outputs"].get("required")
    if required == "false":
        return "Benchmark measurements were not required for this change."
    if required != "true":
        raise ValueError("Benchmark change detection returned no valid decision")
    prepare = needs["prepare"]
    if prepare["result"] != "success":
        raise ValueError("Benchmark preparation did not succeed")
    bencher = prepare["outputs"].get("bencher")
    if bencher not in ("true", "false"):
        raise ValueError("Benchmark preparation returned no valid runner selection")
    runner = "bencher" if bencher == "true" else "measure"
    if needs[runner]["result"] != "success":
        raise ValueError(f"Benchmark runner {runner} failed, was cancelled, or did not run")
    if needs["coverage"]["result"] != "success":
        raise ValueError("Benchmark result coverage did not succeed")
    if bencher == "true":
        return "All benchmark jobs passed measurement, regression, and coverage checks."
    return "All GitHub-runner benchmark jobs passed execution and coverage checks."


def main():
    try:
        if sys.argv[1:] == ["changes"]:
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
            required, message = measurement_decision(os.environ["GITHUB_EVENT_NAME"], event)
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                output.write(f"required={'true' if required else 'false'}\n")
        elif sys.argv[1:] == ["check"]:
            message = check_gate(json.loads(os.environ["BENCHMARK_NEEDS"]))
        else:
            raise ValueError("Usage: benchmark_gate.py changes|check")
        print(message)
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f"Benchmark gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
