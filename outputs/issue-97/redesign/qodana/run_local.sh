#!/bin/bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project="$(cd -- "$script_dir/../../../.." && pwd)"
image="jetbrains/qodana-jvm-community:2026.2"
local_root="$script_dir/local"
# This must be a Linux Gradle cache, not the macOS ~/.gradle cache.
gradle_cache="${QODANA_GRADLE_CACHE:-/Users/sijun-yang/Documents/GitHub/bracket-pair-guides/build/visual-test-background/cache/gradle}"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  printf 'Required local image missing. Acquire it first: docker pull --platform linux/amd64 %s\n' "$image" >&2
  exit 1
fi
test -d "$gradle_cache"
mkdir -p "$local_root/cache"
results_dir="$(mktemp -d "$local_root/results.XXXXXX")"
source_dir="$(mktemp -d "$local_root/source.XXXXXX")"
printf 'Qodana source: %s\nQodana results: %s\n' "$source_dir" "$results_dir"
tar -C "$project" --exclude=.git --exclude=.gradle --exclude=.kotlin --exclude=.idea --exclude=.intellijPlatform --exclude=.qodana --exclude=build --exclude=out --exclude=outputs --exclude=allure-results -cf - . | tar -C "$source_dir" -xf -
exec docker run --rm --init --pull never --platform linux/amd64 \
  --env QODANA_TOKEN= --env GRADLE_USER_HOME=/data/cache/gradle \
  --volume "$source_dir:/data/project" --volume "$results_dir:/data/results" \
  --volume "$local_root/cache:/data/cache" --volume "$gradle_cache:/data/cache/gradle" \
  "$image" --config qodana.yml --project-dir /data/project \
  --results-dir /data/results --cache-dir /data/cache \
  --property=idea.headless.enable.statistics=false
