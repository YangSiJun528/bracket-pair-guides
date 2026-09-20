"""Verify measured dependency selection and fail-closed benchmark enforcement."""

import copy
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import benchmark_gate


class BenchmarkChangesTest(unittest.TestCase):
    def setUp(self):
        self.event = {"pull_request": {
            "draft": False,
            "labels": [],
            "base": {"sha": "a" * 40},
            "head": {"sha": "b" * 40},
        }}

    def test_only_measured_dependencies_trigger(self):
        root = "plugin/src/main/"
        java = root + "java/com/sijunyang/bracketpairguides/"
        kotlin = root + "kotlin/com/sijunyang/bracketpairguides/"
        cases = {
            java + "analysis/pairing/core/PairingMachine.java": True,
            java + "analysis/pairing/core/NewHelper.java": True,
            kotlin + "analysis/sorting/CancellableLongArraySort.kt": True,
            kotlin + "preferences/BracketGuidePreferences.kt": True,
            kotlin + "preferences/StoredColorFormat.kt": True,
            kotlin + "settings/BracketGuidePreferenceNormalization.kt": True,
            kotlin + "preferences/GuideAnalysisCoverage.kt": False,
            kotlin + "analysis/intellij/BracketAnalysis.kt": False,
            kotlin + "analysis/pairing/DocumentBrackets.kt": False,
            kotlin + "presentation/GuideAppearance.kt": False,
            kotlin + "editor/EditorGuideSession.kt": False,
            kotlin + "settings/ui/BracketGuideSettingsPage.kt": False,
            java + "editor/events/NativeMatchedBracePluginUnloadListener.java": False,
            root + "resources/META-INF/plugin.xml": False,
            "plugin/src/test/kotlin/ExampleTest.kt": False,
            "plugin/src/visualTest/resources/baseline.png": False,
            "benchmarks/src/jmh/java/ExampleBenchmark.java": True,
            "benchmarks/src/main/resources/input.txt": True,
            "benchmarks/bencher/run_jobs.py": True,
            "benchmarks/bencher/benchmark_gate.py": True,
            "benchmarks/bencher/run.sh": True,
            "benchmarks/bencher/Dockerfile": True,
            "benchmarks/bencher/Dockerfile.dockerignore": True,
            "benchmarks/bencher/test_run_jobs.py": False,
            "benchmarks/bencher/test_benchmark_gate.py": False,
            "benchmarks/guide_bencher.md": False,
            "benchmarks/results/results.json": False,
            "benchmarks/ide-comparison/build.gradle.kts": False,
            "benchmarks/check_job_results.py": True,
            "build.gradle.kts": True,
            "settings.gradle.kts": True,
            "plugin/build.gradle.kts": True,
            "benchmarks/build.gradle.kts": True,
            "gradle.properties": True,
            "gradle/wrapper/gradle-wrapper.properties": True,
            "gradle/wrapper/gradle-wrapper.jar": True,
            "gradlew": True,
            "gradlew.bat": False,
            ".github/workflows/benchmark-jobs.yml": True,
            ".github/workflows/build.yml": False,
            "README.md": False,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(benchmark_gate.affects_benchmarks(path), expected)

    def test_manual_and_new_branch_runs_measure_without_diff(self):
        with patch("benchmark_gate.changed_paths") as diff:
            self.assertTrue(benchmark_gate.measurement_decision("workflow_dispatch", {})[0])
            self.assertTrue(benchmark_gate.measurement_decision(
                "push", {"before": "0" * 40, "after": "b" * 40},
            )[0])
        diff.assert_not_called()

    def test_draft_and_skip_ci_prs_skip_without_diff(self):
        for field, value in (("draft", True), ("labels", [{"name": "skip-ci"}])):
            with self.subTest(field=field):
                event = copy.deepcopy(self.event)
                event["pull_request"][field] = value
                with patch("benchmark_gate.changed_paths") as diff:
                    self.assertFalse(benchmark_gate.measurement_decision("pull_request", event)[0])
                diff.assert_not_called()

    def test_prs_recheck_complete_diff_even_for_unrelated_label_events(self):
        self.event["action"] = "labeled"
        self.event["pull_request"]["labels"] = [{"name": "documentation"}]
        for paths, expected in ((["README.md"], False), (["README.md", "benchmarks/bencher/run.sh"], True)):
            with self.subTest(paths=paths):
                with patch("benchmark_gate.changed_paths", return_value=paths) as diff:
                    self.assertEqual(benchmark_gate.measurement_decision("pull_request", self.event)[0], expected)
                diff.assert_called_once_with("a" * 40, "b" * 40, True)

    def test_push_uses_before_and_after_commits(self):
        with patch("benchmark_gate.changed_paths", return_value=[]) as diff:
            self.assertFalse(benchmark_gate.measurement_decision(
                "push", {"before": "a" * 40, "after": "b" * 40},
            )[0])
        diff.assert_called_once_with("a" * 40, "b" * 40, False)

    def test_unknown_events_and_invalid_hashes_fail(self):
        with self.assertRaises(ValueError):
            benchmark_gate.measurement_decision("unknown", {})
        with patch("benchmark_gate.subprocess.run") as run:
            with self.assertRaises(ValueError):
                benchmark_gate.changed_paths("--invalid", "b" * 40, True)
        run.assert_not_called()

    def test_real_git_diff_includes_earlier_pr_commits_and_both_sides_of_renames(self):
        execute = subprocess.run
        with tempfile.TemporaryDirectory() as directory:
            def git(*arguments):
                return execute(["git", *arguments], cwd=directory, check=True, capture_output=True).stdout.decode().strip()

            git("init", "-q")
            git("config", "user.name", "Benchmark Gate Test")
            git("config", "user.email", "benchmark-gate@example.invalid")
            measured = Path(directory) / "benchmarks/src/input.txt"
            measured.parent.mkdir(parents=True)
            measured.write_text("original")
            git("add", ".")
            git("commit", "-qm", "initial")
            base = git("rev-parse", "HEAD")
            measured.write_text("changed")
            git("commit", "-qam", "measured change")
            previous = git("rev-parse", "HEAD")
            (Path(directory) / "README.md").write_text("documentation only")
            git("add", ".")
            git("commit", "-qm", "documentation")
            head = git("rev-parse", "HEAD")

            def run(command, **kwargs):
                return execute(command, cwd=directory, **kwargs)

            with patch("benchmark_gate.subprocess.run", side_effect=run):
                self.assertEqual(set(benchmark_gate.changed_paths(base, head, True)), {
                    "benchmarks/src/input.txt", "README.md",
                })
                self.assertEqual(benchmark_gate.changed_paths(previous, head, False), ["README.md"])
            git("mv", "benchmarks/src/input.txt", "moved.txt")
            git("commit", "-qm", "move out of measured directory")
            moved = git("rev-parse", "HEAD")
            with patch("benchmark_gate.subprocess.run", side_effect=run):
                self.assertEqual(set(benchmark_gate.changed_paths(head, moved, False)), {
                    "benchmarks/src/input.txt", "moved.txt",
                })


class BenchmarkGateTest(unittest.TestCase):
    def setUp(self):
        self.needs = {
            "changes": {"result": "success", "outputs": {"required": "true"}},
            "prepare": {"result": "success", "outputs": {"bencher": "true"}},
            "bencher": {"result": "success"},
            "measure": {"result": "skipped"},
            "coverage": {"result": "success"},
        }

    def test_successful_bencher_and_fallback_runs_pass(self):
        self.assertIn("regression", benchmark_gate.check_gate(self.needs))
        self.needs["prepare"]["outputs"]["bencher"] = "false"
        self.needs["bencher"]["result"] = "skipped"
        self.needs["measure"]["result"] = "success"
        self.assertIn("GitHub-runner", benchmark_gate.check_gate(self.needs))

    def test_unrelated_changes_pass_when_measurement_jobs_are_skipped(self):
        self.needs["changes"]["outputs"]["required"] = "false"
        for name in ("prepare", "bencher", "measure", "coverage"):
            self.needs[name] = {"result": "skipped", "outputs": {}}
        self.assertIn("not required", benchmark_gate.check_gate(self.needs))

    def test_failed_cancelled_or_skipped_required_jobs_fail(self):
        for name in ("changes", "prepare", "bencher", "coverage"):
            for result in ("failure", "cancelled", "skipped"):
                with self.subTest(job=name, result=result):
                    needs = copy.deepcopy(self.needs)
                    needs[name]["result"] = result
                    with self.assertRaises(ValueError):
                        benchmark_gate.check_gate(needs)

    def test_failed_fallback_measurements_fail(self):
        self.needs["prepare"]["outputs"]["bencher"] = "false"
        self.needs["measure"]["result"] = "failure"
        with self.assertRaises(ValueError):
            benchmark_gate.check_gate(self.needs)

    def test_failed_detection_cannot_skip_the_gate(self):
        self.needs["changes"] = {"result": "failure", "outputs": {"required": "false"}}
        with self.assertRaises(ValueError):
            benchmark_gate.check_gate(self.needs)

    def test_missing_outputs_cannot_skip_the_gate(self):
        for name in ("changes", "prepare"):
            with self.subTest(job=name):
                needs = copy.deepcopy(self.needs)
                needs[name]["outputs"] = {}
                with self.assertRaises(ValueError):
                    benchmark_gate.check_gate(needs)


if __name__ == "__main__":
    unittest.main()
