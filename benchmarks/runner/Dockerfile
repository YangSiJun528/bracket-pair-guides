# Same bare-metal guest/JVM as the original benchmark environment.
FROM eclipse-temurin:17-jdk-jammy@sha256:51d32af96fb8a13ffbc96c719d7b05447a04ca69f314627d64050a64ecd13057
RUN apt-get update && apt-get install -y --no-install-recommends jq iproute2 && rm -rf /var/lib/apt/lists/*
WORKDIR /bench
COPY benchmarks/build/benchmark-jobs/jmh.jar /bench/jmh.jar
COPY benchmarks/runner/guest.sh benchmarks/runner/metrics.jq /bench/
ENTRYPOINT ["/bin/bash", "/bench/guest.sh"]
