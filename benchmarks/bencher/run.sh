#!/bin/sh
set -eu

case "${BENCHMARK_JOB:-}" in
    ''|[!a-z0-9]*|*[!a-z0-9-]*)
        echo 'BENCHMARK_JOB must be a prepared benchmark job name.' >&2
        exit 2
        ;;
esac

# Bencher's guest boots without network initialization. JMH's two JVM forks
# communicate over loopback; bringing up only lo keeps the guest offline.
if [ "$(( $(cat /sys/class/net/lo/flags) & 1 ))" -eq 0 ]; then
    echo 'Enabling guest loopback for JMH fork communication.' >&2
    ip link set dev lo up
fi
cd "/bench/jobs/$BENCHMARK_JOB"

# A failed or timed-out JVM must not leave usable partial measurements behind.
rm -f results.json human.txt /bench/bencher-results.json
status=0
timeout --signal=TERM --kill-after=10s 240s java -Djmh.link.address=127.0.0.1 @run.args || status=$?
if [ -f human.txt ]; then
    cat human.txt >&2
fi
if [ "$status" -ne 0 ]; then
    exit "$status"
fi
python3 /bench/normalize_results.py results.json /bench/bencher-results.json
