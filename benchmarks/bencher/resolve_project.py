"""Resolve the public Bencher project authorized by a project-scoped API key."""

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


PROJECTS_URL = "https://api.bencher.dev/v0/projects"
MAX_RESPONSE_BYTES = 1_000_000


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Never forward the API credential to a redirect destination.
        raise urllib.error.HTTPError(request.full_url, code, message, headers, response)


def fetch_projects(api_key):
    request = urllib.request.Request(
        PROJECTS_URL,
        headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json"},
        method="GET",
    )
    try:
        opener = urllib.request.build_opener(NoRedirect)
        with opener.open(request, timeout=15) as response:
            contents = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        raise RuntimeError(f"Bencher project lookup failed (HTTP {status})") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise RuntimeError("Could not reach the official Bencher API") from None
    if len(contents) > MAX_RESPONSE_BYTES:
        raise ValueError("Bencher returned an unexpectedly large project response")
    try:
        return json.loads(contents)
    except (ValueError, UnicodeError):
        raise ValueError("Bencher returned invalid project JSON") from None


def resolve_project(environment=None):
    environment = os.environ if environment is None else environment
    explicit_project = environment.get("BENCHER_PROJECT", "")
    api_key = environment.get("BENCHER_API_KEY", "")
    if not api_key:
        if explicit_project:
            raise ValueError("BENCHER_PROJECT is set but BENCHER_API_KEY is missing")
        return ""
    if not api_key.startswith("bencher_run_"):
        raise ValueError(
            "BENCHER_API_KEY must be a project-scoped key beginning with bencher_run_; "
            "user keys are not supported by this registry login"
        )

    # Bencher v0.6.12 scopes this endpoint to the key's project. Never select a
    # project merely by matching its name against the public project catalog.
    projects = fetch_projects(api_key)
    if not isinstance(projects, list) or len(projects) != 1 or not isinstance(projects[0], dict):
        raise ValueError("The project API key must resolve to exactly one Bencher project")
    project = projects[0]
    if project.get("visibility") != "public":
        raise ValueError("Bencher Free requires this benchmark project to be public")
    slug = project.get("slug")
    if not isinstance(slug, str) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", slug):
        raise ValueError("Bencher returned an invalid project slug")
    if explicit_project and explicit_project != slug:
        raise ValueError("BENCHER_PROJECT does not match the project authorized by BENCHER_API_KEY")
    return slug


def main():
    try:
        project = resolve_project()
        if output_path := os.environ.get("GITHUB_OUTPUT"):
            with Path(output_path).open("a", encoding="utf-8") as output:
                output.write(f"project={project}\nbencher={'true' if project else 'false'}\n")
        print(project)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Bencher project lookup failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
