#!/usr/bin/env bash
set -euo pipefail
# CI calls this only for trusted repositories/actors. Never enable tracing here.
project=${BENCHER_PROJECT:-}
key=${BENCHER_API_KEY:-}
if [[ -z "$key" ]]; then
  [[ -z "$project" ]] || { echo 'Configured Bencher project has no project API key.' >&2; exit 1; }
  printf 'bencher=false\nproject=\n' >> "$GITHUB_OUTPUT"
  exit 0
fi
[[ "$key" == bencher_run_* ]] || { echo 'Use a project-scoped Bencher API key.' >&2; exit 1; }
response=$(curl --fail --silent --show-error --max-time 15 --max-filesize 1000000 \
  --header "Authorization: Bearer $key" --header 'Accept: application/json' https://api.bencher.dev/v0/projects)
resolved=$(jq -er 'if type == "array" and length == 1 and .[0].visibility == "public" and (.[0].slug | test("^[a-z0-9]+(-[a-z0-9]+)*$")) then .[0].slug else error("Expected one authorized public project") end' <<< "$response")
[[ -z "$project" || "$project" == "$resolved" ]] || { echo 'Project variable differs from key scope.' >&2; exit 1; }
printf 'bencher=true\nproject=%s\n' "$resolved" >> "$GITHUB_OUTPUT"
