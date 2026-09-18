"""Check remote orchestration without creating Bencher jobs or using credentials."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_jobs


class RunJobsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.jobs_path = self.root / "jobs.json"
        self.jobs_path.write_text(json.dumps(["pairing", "preferences"]))
        self.results = self.root / "results"
        self.environment = {
            "BENCHER_API_KEY": "secret-bencher-key",
            "BENCHER_PROJECT": "test-project",
            "BENCHER_IMAGE": "test-project:test-commit",
            "BENCHER_BRANCH": "pr-123",
            "BENCHER_HASH": "a" * 40,
            "BENCHER_BASE_BRANCH": "main",
            "BENCHER_BASE_SHA": "b" * 40,
            "BENCHER_PR_NUMBER": "123",
            "GITHUB_TOKEN": "secret-github-token",
        }

    @staticmethod
    def report(job="remote-job", report="remote-report"):
        return {"uuid": report, "job": job}

    @staticmethod
    def job_output(status="processed", exit_code=0):
        results = [
            {
                "benchmark": f"example.Benchmark.run[size={size}]",
                "originalBenchmark": "example.Benchmark.run",
                "params": {"size": size},
                "forks": 2,
                "warmupIterations": 2,
                "measurementIterations": 3,
                "primaryMetric": {"score": 1.0},
                "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 2.0}},
            }
            for size in ("100", "1000")
        ]
        return {
            "status": status,
            "output": {"results": [{
                "exit_code": exit_code,
                "stdout": "Job stdout",
                "stderr": "JMH human output",
                "output": {run_jobs.REMOTE_RESULTS: json.dumps(results)},
            }]},
        }

    @staticmethod
    def fake_responses(responses):
        def run(command, *, stdout, env, check, timeout):
            contents, code = next(responses)
            stdout.write(contents if isinstance(contents, str) else json.dumps(contents))
            return subprocess.CompletedProcess(command, code)
        return run

    def run_with(self, responses):
        return patch("run_jobs.subprocess.run", side_effect=self.fake_responses(iter(responses)))

    def test_runs_sequentially_preserves_parameters_and_resets_branch_once(self):
        responses = [(self.report(), 0), (self.job_output(), 0)] * 2
        with self.run_with(responses) as process:
            run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        commands = [call.args[0] for call in process.call_args_list]
        self.assertEqual([command[1] for command in commands], ["run", "job", "run", "job"])
        first, second = commands[0], commands[2]
        self.assertIn("--start-point-reset", first)
        self.assertNotIn("--start-point-reset", second)
        self.assertIn("--start-point-clone-thresholds", first)
        self.assertNotIn("--start-point-clone-thresholds", second)
        self.assertNotIn("--threshold-test", first)
        for command, job in ((first, "pairing"), (second, "preferences")):
            self.assertEqual(command[command.index("--env") + 1], f"BENCHMARK_JOB={job}")
            self.assertEqual(command[command.index("--ci-id") + 1], f"benchmark-{job}")
            self.assertEqual(command[command.index("--ci-number") + 1], "123")
            self.assertEqual(command[command.index("--hash") + 1], "a" * 40)
            self.assertEqual(command[command.index("--file") + 1], run_jobs.REMOTE_RESULTS)
            self.assertNotIn(self.environment["BENCHER_API_KEY"], command)
            self.assertNotIn("--error-on-alert", command)
            self.assertIn("--quiet", command)
        results = json.loads((self.results / "pairing" / "results.json").read_text())
        self.assertEqual([row["benchmark"] for row in results], ["example.Benchmark.run"] * 2)
        self.assertEqual([row["params"]["size"] for row in results], ["100", "1000"])
        self.assertTrue(all("originalBenchmark" not in row for row in results))
        self.assertEqual(results[0]["secondaryMetrics"]["gc.alloc.rate.norm"]["score"], 2.0)
        self.assertIn("JMH human output", (self.results / "pairing" / "human.txt").read_text())

    def test_failed_run_retains_available_artifacts_and_stops(self):
        with self.run_with([(self.report(), 1), (self.job_output(), 0)]) as process:
            with self.assertRaisesRegex(RuntimeError, "artifacts were retained"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        self.assertEqual(process.call_count, 2)
        self.assertTrue((self.results / "pairing" / "results.json").exists())
        self.assertFalse((self.results / "preferences").exists())

    def test_missing_report_stops_without_starting_another_job(self):
        with self.run_with([("", 1)]) as process:
            with self.assertRaisesRegex(RuntimeError, "no remote job report"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        self.assertEqual(process.call_count, 1)

    def test_nonterminal_remote_job_stops(self):
        with self.run_with([(self.report(), 0), (self.job_output(status="running"), 0)]) as process:
            with self.assertRaisesRegex(RuntimeError, "not terminal"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        self.assertEqual(process.call_count, 2)
        self.assertFalse((self.results / "pairing" / "results.json").exists())

    def test_missing_result_file_stops(self):
        output = self.job_output()
        output["output"]["results"][0]["output"] = {}
        with self.run_with([(self.report(), 0), (output, 0)]) as process:
            with self.assertRaisesRegex(RuntimeError, "did not return"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        self.assertEqual(process.call_count, 2)

    def test_timeout_message_does_not_include_credentials(self):
        with patch("run_jobs.subprocess.run", side_effect=subprocess.TimeoutExpired(
            ["bencher", "--github-actions", self.environment["GITHUB_TOKEN"]], 750,
        )):
            with self.assertRaises(RuntimeError) as error:
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        self.assertIn("may still be running", str(error.exception))
        self.assertNotIn(self.environment["GITHUB_TOKEN"], str(error.exception))

    def test_invalid_pr_metadata_fails_before_submission(self):
        del self.environment["BENCHER_BASE_SHA"]
        with patch("run_jobs.subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "Set both"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        process.assert_not_called()

    def test_main_run_has_no_pr_options(self):
        for name in ("BENCHER_BASE_BRANCH", "BENCHER_BASE_SHA", "BENCHER_PR_NUMBER", "GITHUB_TOKEN"):
            self.environment.pop(name)
        command = run_jobs.command_for_job("pairing", self.environment, True)
        self.assertNotIn("--start-point", command)
        self.assertNotIn("--start-point-reset", command)
        self.assertNotIn("--github-actions", command)
        self.assertEqual(command[command.index("--threshold-test") + 1], "percentage")
        self.assertEqual(command[command.index("--threshold-upper-boundary") + 1], "0.20")
        self.assertEqual(command[command.index("--threshold-max-sample-size") + 1], "1")
        second = run_jobs.command_for_job("preferences", self.environment, False)
        self.assertNotIn("--threshold-measure", second)

    def test_invalid_job_names_fail_before_submission(self):
        self.jobs_path.write_text('["../escape"]')
        with patch("run_jobs.subprocess.run") as process:
            with self.assertRaisesRegex(ValueError, "job list"):
                run_jobs.run_jobs(self.jobs_path, self.results, self.environment)
        process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
