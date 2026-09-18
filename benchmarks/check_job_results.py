"""Check that independent full-length jobs cover the unfiltered JMH suite exactly once."""

import json
import math
import sys
from pathlib import Path


def identity(result):
    return result["benchmark"], tuple(sorted(result.get("params", {}).items()))


def check(reference_path, results_directory, jobs_path):
    reference = json.loads(reference_path.read_text())
    expected = {identity(result) for result in reference}
    if not expected:
        raise ValueError("The unfiltered reference contains no benchmarks")
    jobs = json.loads(jobs_path.read_text())
    if not jobs or len(jobs) != len(set(jobs)):
        raise ValueError("The job list must be nonempty and unique")
    observed = {}
    for job in jobs:
        results = json.loads((results_directory / job / "results.json").read_text())
        if not results:
            raise ValueError(f"{job}: no benchmark results")
        for result in results:
            case = identity(result)
            if case in observed:
                raise ValueError(f"Duplicate benchmark in {observed[case]} and {job}: {case}")
            observed[case] = job
            score = result["primaryMetric"]["score"]
            if not isinstance(score, (int, float)) or not math.isfinite(score):
                raise ValueError(f"{job}: invalid score for {case}")
            profile = result["forks"], result["warmupIterations"], result["measurementIterations"]
            if profile != (2, 2, 3):
                raise ValueError(f"{job}: expected full measurement profile, got {profile}")
    missing = expected - observed.keys()
    extra = observed.keys() - expected
    if missing or extra:
        raise ValueError(f"Partition mismatch: missing={sorted(missing)}, extra={sorted(extra)}")
    print(f"Verified {len(expected)} benchmark cases across {len(jobs)} jobs, with no gaps or duplicates.")


if __name__ == "__main__":
    check(*(Path(argument) for argument in sys.argv[1:]))
