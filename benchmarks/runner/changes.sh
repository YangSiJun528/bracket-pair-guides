#!/usr/bin/env bash
set -euo pipefail
required=true
case "$GITHUB_EVENT_NAME" in
  workflow_dispatch) ;;
  pull_request)
    if jq -e '.pull_request.draft or any(.pull_request.labels[]; .name == "skip-ci")' "$GITHUB_EVENT_PATH" >/dev/null; then
      required=false
    else
      base=$(jq -r '.pull_request.base.sha' "$GITHUB_EVENT_PATH")
      head=$(jq -r '.pull_request.head.sha' "$GITHUB_EVENT_PATH")
      comparison="$base...$head"
    fi ;;
  push)
    base=$(jq -r '.before' "$GITHUB_EVENT_PATH")
    head=$(jq -r '.after' "$GITHUB_EVENT_PATH")
    [[ "$base" == 0000000000000000000000000000000000000000 ]] || comparison="$base..$head" ;;
  *) echo 'Unsupported benchmark event.' >&2; exit 1 ;;
esac
if [[ -n "${comparison:-}" ]]; then
  [[ "$base" =~ ^[0-9a-f]{40}$ && "$head" =~ ^[0-9a-f]{40}$ ]] || exit 1
  required=false
  changed=$(mktemp)
  trap 'rm -f "$changed"' EXIT
  git diff --name-only --no-renames -z "$comparison" -- > "$changed"
  while IFS= read -r -d '' path; do
    case "$path" in
      analysis-model/src/main/*|analysis-core/src/main/*|editor-ui/src/main/*|analysis-runtime/src/main/*|plugin/src/main/*|benchmarks/src/*|benchmarks/runner/*|build-logic/*|tools/pure-build/*|gradle/*|gradlew|gradle.properties|*.gradle.kts|.github/workflows/benchmark-jobs.yml) required=true ;;
    esac
  done < "$changed"
fi
printf 'required=%s\n' "$required" >> "$GITHUB_OUTPUT"
