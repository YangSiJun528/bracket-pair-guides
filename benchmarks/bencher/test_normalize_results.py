import copy
import json
import tempfile
import unittest
from pathlib import Path

from normalize_results import normalize_file, normalize_results


def result(params=None, score=1.5):
    entry = {
        "benchmark": "example.AnalysisBenchmark.analyze",
        "mode": "avgt",
        "forks": 2,
        "warmupIterations": 2,
        "measurementIterations": 3,
        "primaryMetric": {
            "score": score,
            "scoreUnit": "ns/op",
            "scoreError": "NaN",
            "scoreConfidence": ["NaN", "NaN"],
            "rawData": [[score]],
        },
        "secondaryMetrics": {
            "gc.alloc.rate.norm": {"score": 16.0, "scoreUnit": "B/op"}
        },
    }
    if params is not None:
        entry["params"] = params
    return entry


class NormalizeResultsTest(unittest.TestCase):
    def test_parameterized_cases_have_distinct_names_and_keep_all_other_data(self):
        raw = [
            result({"distribution": "random", "count": "10"}),
            result({"distribution": "random", "count": "20"}),
        ]
        original = copy.deepcopy(raw)
        normalized = normalize_results(raw)

        self.assertEqual(raw, original)
        self.assertEqual(len(normalized), 2)
        self.assertEqual(
            normalized[0]["benchmark"],
            'example.AnalysisBenchmark.analyze {"count":"10","distribution":"random"}',
        )
        self.assertNotEqual(normalized[0]["benchmark"], normalized[1]["benchmark"])
        for source, adapted in zip(raw, normalized):
            self.assertEqual(adapted["originalBenchmark"], source["benchmark"])
            self.assertEqual(
                {
                    key: value
                    for key, value in adapted.items()
                    if key not in ("benchmark", "originalBenchmark")
                },
                {key: value for key, value in source.items() if key != "benchmark"},
            )

    def test_identity_is_independent_of_parameter_order(self):
        first = normalize_results([result({"b": "2", "a": "1"})])
        second = normalize_results([result({"a": "1", "b": "2"})])
        self.assertEqual(first[0]["benchmark"], second[0]["benchmark"])

    def test_missing_and_empty_params_leave_name_unchanged(self):
        for entry in (result(), result({})):
            with self.subTest(entry=entry):
                self.assertEqual(
                    normalize_results([entry])[0],
                    {**entry, "originalBenchmark": entry["benchmark"]},
                )

    def test_rejects_already_normalized_input(self):
        normalized = normalize_results([result({"count": "10"})])
        with self.assertRaisesRegex(ValueError, "already contains originalBenchmark"):
            normalize_results(normalized)

    def test_rejects_duplicate_normalized_names(self):
        cases = [
            [result(), result({})],
            [result({"b": "2", "a": "1"}), result({"a": "1", "b": "2"})],
        ]
        for entries in cases:
            with self.subTest(entries=entries):
                with self.assertRaisesRegex(ValueError, "Duplicate normalized benchmark"):
                    normalize_results(entries)

    def test_rejects_invalid_primary_scores(self):
        for score in (float("nan"), float("inf"), -float("inf"), True, "NaN", None):
            with self.subTest(score=score):
                with self.assertRaisesRegex(ValueError, "finite number"):
                    normalize_results([result(score=score)])

    def test_rejects_missing_primary_metric(self):
        entry = result()
        del entry["primaryMetric"]
        with self.assertRaisesRegex(ValueError, "finite number"):
            normalize_results([entry])

    def test_rejects_empty_or_non_list_input(self):
        for entries in ([], {}, None, "results"):
            with self.subTest(entries=entries):
                with self.assertRaisesRegex(ValueError, "nonempty list"):
                    normalize_results(entries)

    def test_rejects_invalid_result_shapes(self):
        for entry in (None, [], "result"):
            with self.subTest(entry=entry):
                with self.assertRaisesRegex(ValueError, "expected an object"):
                    normalize_results([entry])
        for benchmark in (None, 7, "", " "):
            with self.subTest(benchmark=benchmark):
                entry = {**result(), "benchmark": benchmark}
                with self.assertRaisesRegex(ValueError, "nonempty string"):
                    normalize_results([entry])
        for params in (None, [], "count=1", {"count": 1}, {1: "count"}):
            with self.subTest(params=params):
                entry = {**result(), "params": params}
                with self.assertRaisesRegex(ValueError, "map strings to strings"):
                    normalize_results([entry])

    def test_file_conversion_preserves_raw_input(self):
        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "results.json"
            output_path = Path(directory) / "bencher-results.json"
            raw_text = json.dumps([result({"count": "10"})])
            raw_path.write_text(raw_text, encoding="utf-8")

            self.assertEqual(normalize_file(raw_path, output_path), 1)
            self.assertEqual(raw_path.read_text(encoding="utf-8"), raw_text)
            adapted = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(adapted, normalize_results(json.loads(raw_text)))

    def test_rejects_overwriting_raw_input(self):
        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "results.json"
            raw_text = json.dumps([result()])
            raw_path.write_text(raw_text, encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "must differ"):
                normalize_file(raw_path, raw_path)
            self.assertEqual(raw_path.read_text(encoding="utf-8"), raw_text)

    def test_invalid_results_do_not_write_output(self):
        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "results.json"
            output_path = Path(directory) / "bencher-results.json"
            raw_path.write_text("[]", encoding="utf-8")

            with self.assertRaises(ValueError):
                normalize_file(raw_path, output_path)
            self.assertFalse(output_path.exists())


if __name__ == "__main__":
    unittest.main()
