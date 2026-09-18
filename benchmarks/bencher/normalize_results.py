"""Keep parameterized JMH cases distinct in Bencher's java_jmh adapter."""

import argparse
import json
import math
from pathlib import Path


def normalize_results(results):
    if not isinstance(results, list) or not results:
        raise ValueError("JMH results must be a nonempty list")

    normalized = []
    identities = set()
    for index, result in enumerate(results):
        if not isinstance(result, dict):
            raise ValueError(f"Result {index}: expected an object")
        benchmark = result.get("benchmark")
        if not isinstance(benchmark, str) or not benchmark.strip():
            raise ValueError(f"Result {index}: benchmark must be a nonempty string")
        if "originalBenchmark" in result:
            raise ValueError(f"{benchmark}: input already contains originalBenchmark")
        params = result.get("params", {})
        if not isinstance(params, dict) or any(
            not isinstance(key, str) or not isinstance(value, str)
            for key, value in params.items()
        ):
            raise ValueError(f"{benchmark}: params must map strings to strings")

        primary_metric = result.get("primaryMetric")
        score = primary_metric.get("score") if isinstance(primary_metric, dict) else None
        if (
            isinstance(score, bool)
            or not isinstance(score, (int, float))
            or (isinstance(score, float) and not math.isfinite(score))
        ):
            raise ValueError(f"{benchmark}: primaryMetric.score must be a finite number")

        if params:
            benchmark += " " + json.dumps(
                params, sort_keys=True, separators=(",", ":"), ensure_ascii=False
            )
        if benchmark in identities:
            raise ValueError(f"Duplicate normalized benchmark: {benchmark}")
        identities.add(benchmark)
        normalized.append(
            {**result, "benchmark": benchmark, "originalBenchmark": result["benchmark"]}
        )
    return normalized


def normalize_file(input_path, output_path):
    if input_path.resolve() == output_path.resolve():
        raise ValueError("The output path must differ from the raw JMH input path")
    results = json.loads(input_path.read_text(encoding="utf-8"))
    normalized = normalize_results(results)
    output_path.write_text(
        json.dumps(normalized, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return len(normalized)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Raw JMH results.json")
    parser.add_argument("output", type=Path, help="Normalized Bencher JMH JSON")
    args = parser.parse_args()
    try:
        count = normalize_file(args.input, args.output)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    print(f"Prepared {count} distinct JMH cases for Bencher.")


if __name__ == "__main__":
    main()
