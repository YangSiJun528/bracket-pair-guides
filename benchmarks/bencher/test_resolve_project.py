import contextlib
import io
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

import resolve_project


class ResolveProjectTest(unittest.TestCase):
    def setUp(self):
        self.key = "bencher_run_test_credential_never_print"
        self.environment = {"BENCHER_API_KEY": self.key}
        self.project = {"slug": "bracket-pair-guides", "visibility": "public", "url": None}

    def test_unconfigured_repository_skips_without_request(self):
        with patch("resolve_project.fetch_projects") as fetch:
            self.assertEqual(resolve_project.resolve_project({}), "")
        fetch.assert_not_called()

    def test_explicit_project_requires_key(self):
        with patch("resolve_project.fetch_projects") as fetch:
            with self.assertRaisesRegex(ValueError, "API_KEY is missing"):
                resolve_project.resolve_project({"BENCHER_PROJECT": "configured-project"})
        fetch.assert_not_called()

    def test_user_key_fails_before_request_without_echoing_credential(self):
        user_key = "bencher_user_do_not_print_this_value"
        with patch("resolve_project.fetch_projects") as fetch:
            with self.assertRaises(ValueError) as error:
                resolve_project.resolve_project({"BENCHER_API_KEY": user_key})
        self.assertIn("project-scoped", str(error.exception))
        self.assertNotIn(user_key, str(error.exception))
        fetch.assert_not_called()

    def test_key_scoped_discovery_does_not_require_public_repository_url(self):
        with patch("resolve_project.fetch_projects", return_value=[self.project]) as fetch:
            self.assertEqual(resolve_project.resolve_project(self.environment), "bracket-pair-guides")
        fetch.assert_called_once_with(self.key)

    def test_explicit_project_must_match_key_scope(self):
        with patch("resolve_project.fetch_projects", return_value=[self.project]):
            self.assertEqual(
                resolve_project.resolve_project({**self.environment, "BENCHER_PROJECT": "bracket-pair-guides"}),
                "bracket-pair-guides",
            )
            with self.assertRaisesRegex(ValueError, "does not match"):
                resolve_project.resolve_project({**self.environment, "BENCHER_PROJECT": "another-project"})

    def test_ambiguous_or_invalid_catalog_is_rejected(self):
        for projects in ([], [self.project, self.project], {}, [None]):
            with self.subTest(projects=projects):
                with patch("resolve_project.fetch_projects", return_value=projects):
                    with self.assertRaisesRegex(ValueError, "exactly one"):
                        resolve_project.resolve_project(self.environment)

    def test_private_project_is_rejected(self):
        with patch("resolve_project.fetch_projects", return_value=[{**self.project, "visibility": "private"}]):
            with self.assertRaisesRegex(ValueError, "public"):
                resolve_project.resolve_project(self.environment)

    def test_invalid_slug_cannot_inject_github_outputs(self):
        for slug in (None, "", "project\nbencher=false", "../project", "project:tag"):
            with self.subTest(slug=slug):
                with patch("resolve_project.fetch_projects", return_value=[{**self.project, "slug": slug}]):
                    with self.assertRaisesRegex(ValueError, "invalid project slug"):
                        resolve_project.resolve_project(self.environment)

    def test_http_request_uses_fixed_origin_bearer_and_timeout(self):
        with patch("resolve_project.urllib.request.build_opener") as build:
            build.return_value.open.return_value = io.BytesIO(json.dumps([self.project]).encode())
            self.assertEqual(resolve_project.fetch_projects(self.key), [self.project])
        build.assert_called_once_with(resolve_project.NoRedirect)
        request = build.return_value.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://api.bencher.dev/v0/projects")
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.get_header("Authorization"), f"Bearer {self.key}")
        self.assertEqual(build.return_value.open.call_args.kwargs["timeout"], 15)

    def test_http_errors_never_include_raw_response_or_credential(self):
        error = urllib.error.HTTPError(
            resolve_project.PROJECTS_URL, 401, self.key, {}, io.BytesIO(self.key.encode()),
        )
        with patch("resolve_project.urllib.request.build_opener") as build:
            build.return_value.open.side_effect = error
            with self.assertRaises(RuntimeError) as raised:
                resolve_project.fetch_projects(self.key)
        self.assertEqual(str(raised.exception), "Bencher project lookup failed (HTTP 401)")

    def test_network_errors_are_sanitized(self):
        with patch("resolve_project.urllib.request.build_opener") as build:
            build.return_value.open.side_effect = urllib.error.URLError(self.key)
            with self.assertRaises(RuntimeError) as raised:
                resolve_project.fetch_projects(self.key)
        self.assertNotIn(self.key, str(raised.exception))

    def test_invalid_json_is_sanitized(self):
        with patch("resolve_project.urllib.request.build_opener") as build:
            build.return_value.open.return_value = io.BytesIO(self.key.encode())
            with self.assertRaisesRegex(ValueError, "invalid project JSON"):
                resolve_project.fetch_projects(self.key)

    def test_redirects_do_not_forward_credential(self):
        request = resolve_project.urllib.request.Request(resolve_project.PROJECTS_URL)
        with self.assertRaises(urllib.error.HTTPError) as error:
            resolve_project.NoRedirect().redirect_request(
                request, None, 302, "Found", {}, "https://example.invalid/projects",
            )
        error.exception.close()

    def test_github_outputs_and_stdout_contain_only_safe_project_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            outputs = Path(temporary) / "outputs"
            outputs.write_text("existing=value\n")
            environment = {**self.environment, "GITHUB_OUTPUT": str(outputs)}
            stdout = io.StringIO()
            with patch.dict(os.environ, environment, clear=True):
                with patch("resolve_project.fetch_projects", return_value=[self.project]):
                    with contextlib.redirect_stdout(stdout):
                        self.assertEqual(resolve_project.main(), 0)
            self.assertEqual(outputs.read_text(), "existing=value\nproject=bracket-pair-guides\nbencher=true\n")
            self.assertEqual(stdout.getvalue(), "bracket-pair-guides\n")

    def test_unconfigured_github_outputs_disable_bencher(self):
        with tempfile.TemporaryDirectory() as temporary:
            outputs = Path(temporary) / "outputs"
            with patch.dict(os.environ, {"GITHUB_OUTPUT": str(outputs)}, clear=True):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(resolve_project.main(), 0)
            self.assertEqual(outputs.read_text(), "project=\nbencher=false\n")

    def test_lookup_failure_writes_no_outputs_and_does_not_echo_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            outputs = Path(temporary) / "outputs"
            environment = {**self.environment, "GITHUB_OUTPUT": str(outputs)}
            stderr = io.StringIO()
            with patch.dict(os.environ, environment, clear=True):
                with patch("resolve_project.urllib.request.build_opener") as build:
                    build.return_value.open.side_effect = urllib.error.URLError(self.key)
                    with contextlib.redirect_stderr(stderr):
                        self.assertEqual(resolve_project.main(), 1)
            self.assertFalse(outputs.exists())
            self.assertNotIn(self.key, stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
