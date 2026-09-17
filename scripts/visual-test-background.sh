#!/bin/bash
# Run the existing visual suite on an isolated Linux display.
set -euo pipefail

if [[ $# -gt 0 ]]; then
  if [[ $# -eq 1 && ( "$1" == "--help" || "$1" == "-h" ) ]]; then
    cat <<'USAGE'
Usage: ./scripts/visual-test-background.sh

Runs visualTest in Docker with Xvfb, without using the desktop cursor or focus.
Docker must be running. Current working-tree changes are included.
Compares existing Linux baselines; never records or replaces baselines.
Logs and reports: build/visual-test-background/run.XXXXXX/
Linux dependency cache: build/visual-test-background/cache/
Override the cache with VISUAL_TEST_CACHE_DIR=/absolute/path.
USAGE
    exit 0
  fi
  printf 'Unknown arguments. Use --help for usage.\n' >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
image="bracket-pair-guides-visual-test:ubuntu24-amd64"
output_root="$repo_root/build/visual-test-background"
cache_dir="${VISUAL_TEST_CACHE_DIR:-$output_root/cache}"

if ! command -v docker >/dev/null 2>&1; then
  printf 'Docker is required. Install and start Docker, then rerun this script.\n' >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  printf 'Docker is unavailable. Start Docker and check the active Docker context.\n' >&2
  exit 1
fi
if [[ "$cache_dir" != /* ]]; then
  printf 'VISUAL_TEST_CACHE_DIR must be an absolute path.\n' >&2
  exit 2
fi

mkdir -p "$output_root" "$cache_dir"
cache_dir="$(cd -- "$cache_dir" && pwd)"
results_dir="$(mktemp -d "$output_root/run.XXXXXX")"
printf 'Visual-test results: %s\n' "$results_dir"

docker build --platform linux/amd64 --tag "$image" "$script_dir/visual-test" \
  2>&1 | tee "$results_dir/image-build.log"

# Only results and a Linux-specific dependency cache are writable host mounts.
# No host display or input devices are exposed to the test IDE.
exec docker run --rm --init --platform linux/amd64 --shm-size=1g \
  --volume "$repo_root:/source:ro" \
  --volume "$cache_dir:/cache" \
  --volume "$results_dir:/results" \
  "$image"
