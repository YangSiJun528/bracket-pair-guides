FROM ubuntu:24.04@sha256:69cecf4bbf72d2d44a9eef1b71fb98c7fb973d78af11399deccef19beb008ad9
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jdk openjdk-17-jdk xvfb xauth ca-certificates curl unzip git libxrender1 libxtst6 libxi6 libfreetype6 fontconfig libgtk-3-0 libnss3 libasound2t64 libgbm1 dbus-x11 x11-utils && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH
ENV GRADLE_USER_HOME=/cache/gradle
WORKDIR /workspace
COPY run.sh /usr/local/bin/run-visual-test
ENTRYPOINT ["/bin/bash", "/usr/local/bin/run-visual-test"]
