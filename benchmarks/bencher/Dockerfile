# Temurin 17.0.20+8; keep the measurement JVM fixed across commits.
FROM eclipse-temurin:17-jdk-jammy@sha256:51d32af96fb8a13ffbc96c719d7b05447a04ca69f314627d64050a64ecd13057

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /bench
COPY benchmarks/build/benchmark-jobs/ /bench/jobs/
COPY benchmarks/bencher/run.sh benchmarks/bencher/normalize_results.py /bench/
ENTRYPOINT ["/bin/sh", "/bench/run.sh"]
