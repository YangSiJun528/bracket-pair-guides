#!/usr/bin/env bash
set -euo pipefail
case "${BENCHMARK_JOB:-}" in
  analyzeCold|analyzeReuse|visibleQuery|repair|cancelledAttempt) ;;
  *) echo 'Unknown benchmark job.' >&2; exit 2 ;;
esac
if (( ($(cat /sys/class/net/lo/flags) & 1) == 0 )); then ip link set dev lo up; fi
rm -f /bench/raw-jmh.json /bench/bencher-results.json
# Compilation and image preparation are outside this unchanged hard budget.
timeout --signal=TERM --kill-after=10s 240s java -Djmh.link.address=127.0.0.1 \
  -jar /bench/jmh.jar ".*\.$BENCHMARK_JOB" -bm avgt -wi 2 -w 1s -i 3 -r 1s -f 2 -t 1 \
  -jvmArgs '-Xms2g -Xmx2g' -prof gc -rf json -rff /bench/raw-jmh.json
jq --arg job "$BENCHMARK_JOB" -f /bench/metrics.jq /bench/raw-jmh.json > /bench/bencher-results.json
# Bencher collects the BMF file; preserve original JMH JSON verbatim in job stderr too.
printf '\nISSUE97_RAW_JMH_BEGIN\n' >&2
cat /bench/raw-jmh.json >&2
printf '\nISSUE97_RAW_JMH_END\n' >&2
