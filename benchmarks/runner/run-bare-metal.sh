#!/usr/bin/env bash
set -euo pipefail
# Every remote submission is explicit CI work. Do not run this script for local review.
: "${BENCHER_API_KEY:?}" "${BENCHER_PROJECT:?}" "${BENCHER_IMAGE:?}" "${BENCHER_HASH:?}" "${BENCHER_BRANCH:?}"
[[ -z "${BENCHER_BASE_BRANCH:-}" && -z "${BENCHER_BASE_SHA:-}" || -n "${BENCHER_BASE_BRANCH:-}" && "${BENCHER_BASE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || exit 2
[[ "${BENCHER_SEED_BASELINE:-false}" == false || "${BENCHER_SEED_BASELINE:-false}" == true ]] || exit 2
mkdir -p results
alerted=0
first=true
for job in analyzeCold analyzeReuse visibleQuery repair cancelledAttempt; do
  mkdir -p "results/$job"
  args=(run --project "$BENCHER_PROJECT" --image "$BENCHER_IMAGE" --spec intel-v1 --job-timeout 300
    --env "BENCHMARK_JOB=$job" --branch "$BENCHER_BRANCH" --hash "$BENCHER_HASH"
    --testbed bencher-intel-v1-jdk17 --adapter json --file /bench/bencher-results.json --format json --quiet)
  if [[ "$first" == true ]]; then
    if [[ -n "${BENCHER_BASE_BRANCH:-}" ]]; then
      args+=(--start-point "$BENCHER_BASE_BRANCH" --start-point-hash "$BENCHER_BASE_SHA" --start-point-clone-thresholds --start-point-reset)
    fi
    args+=(--threshold-measure latency --threshold-test percentage --threshold-upper-boundary 0.20 --threshold-max-sample-size 1
      --threshold-measure allocation_bop --threshold-test percentage --threshold-upper-boundary 0.20 --threshold-max-sample-size 1)
  fi
  first=false
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    args+=(--github-actions "$GITHUB_TOKEN" --ci-id "benchmark-$job" --ci-public-links --ci-only-on-alert)
    [[ -z "${BENCHER_PR_NUMBER:-}" ]] || args+=(--ci-number "$BENCHER_PR_NUMBER")
  fi
  status=0
  timeout --signal=TERM --kill-after=10s 750s bencher "${args[@]}" > "results/$job/report.json" || status=$?
  # Never submit another job after a timeout or an unidentifiable/nonterminal remote job.
  uuid=$(jq -er '.job | select(type == "string" and length > 0)' "results/$job/report.json")
  timeout --signal=TERM --kill-after=10s 750s bencher job view "$BENCHER_PROJECT" "$uuid" > "results/$job/job.json"
  jq -e --arg uuid "$uuid" '.uuid == $uuid' "results/$job/job.json" >/dev/null
  jq -e '(.status == "processed" or .status == "failed" or .status == "canceled") and (.output.results | length == 1)' "results/$job/job.json" >/dev/null
  jq -r '.output.results[0] | (.stdout // "") + "\n" + (.stderr // "")' "results/$job/job.json" > "results/$job/human.txt"
  jq -er '.output.results[0].output["/bench/bencher-results.json"] | select(type == "string")' "results/$job/job.json" > "results/$job/bmf.json"
  jq -er '.output.results[0].stderr | capture("ISSUE97_RAW_JMH_BEGIN\n(?<raw>[\\s\\S]*?)\nISSUE97_RAW_JMH_END").raw' "results/$job/job.json" > "results/$job/jmh.json"
  jq -e '.status == "processed" and .output.results[0].exit_code == 0' "results/$job/job.json" >/dev/null
  [[ "$status" == 0 ]] || { echo "Bencher CLI failed for $job; retained artifacts; no further submissions." >&2; exit 1; }
  # Never equate an empty alert list with a regression comparison. The expanded
  # server report must show a computed strict boundary for every submitted metric.
  report_uuid=$(jq -er '.uuid | select(type == "string" and length > 0)' "results/$job/report.json")
  timeout --signal=TERM --kill-after=10s 750s bencher report view "$BENCHER_PROJECT" "$report_uuid" > "results/$job/expanded-report.json"
  # Resolve measure UUIDs through stable resource slugs, never display-name case.
  # Pinned Bencher0.6.12 defines built-in NAME="Latency", SLUG="latency".
  timeout --signal=TERM --kill-after=10s 750s bencher measure view "$BENCHER_PROJECT" latency > "results/$job/latency-measure.json"
  timeout --signal=TERM --kill-after=10s 750s bencher measure view "$BENCHER_PROJECT" allocation-bop > "results/$job/allocation-measure.json"
  latency_uuid=$(jq -er '.uuid | select(type == "string" and length > 0)' "results/$job/latency-measure.json")
  allocation_uuid=$(jq -er '.uuid | select(type == "string" and length > 0)' "results/$job/allocation-measure.json")
  project_uuid=$(jq -er '.project | select(type == "string" and length > 0)' "results/$job/latency-measure.json")
  jq -e --arg project "$project_uuid" '.project == $project' "results/$job/allocation-measure.json" >/dev/null
  jq -e --slurpfile bmf "results/$job/bmf.json" --arg reportUuid "$report_uuid" --arg jobUuid "$uuid" \
    --arg projectUuid "$project_uuid" --arg branch "$BENCHER_BRANCH" --arg latencyUuid "$latency_uuid" \
    --arg allocationUuid "$allocation_uuid" --arg seed "${BENCHER_SEED_BASELINE:-false}" \
    -f benchmarks/runner/history-evidence.jq "results/$job/expanded-report.json" > "results/$job/history-evidence.json"
  jq -e '.alerts | type == "array"' "results/$job/expanded-report.json" >/dev/null
  if ! jq -e '.alerts | length == 0' "results/$job/expanded-report.json" >/dev/null; then alerted=1; fi
  # Compare guest mapping byte-for-value against the independently tested Gradle converter in coverage.
done
[[ "$alerted" == 0 ]] || { echo 'Bencher regression alerts detected; all job artifacts retained.' >&2; exit 1; }
