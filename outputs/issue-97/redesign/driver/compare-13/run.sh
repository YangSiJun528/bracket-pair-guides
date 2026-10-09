#!/bin/bash
set -euo pipefail
unset CI
export VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1
collect() {
  status=$?
  trap - EXIT
  set +e
  printf '%s\n' "$status" > /results/exit-code.txt
  for path in plugin/build/visual-test-artifacts plugin/build/test-results/visualTest plugin/build/reports/tests/visualTest plugin/build/distributions plugin/build/visual-test-distributions plugin/out; do
    if test -d "$path"; then tar --exclude="*/system" -cf - "$path" | tar -C /results -xf -; fi
  done
  exit "$status"
}
trap collect EXIT
mkdir -p /workspace
tar -C /source --exclude=.git --exclude=.gradle --exclude=.kotlin --exclude=.idea --exclude=.intellijPlatform --exclude=.qodana --exclude=build --exclude=out --exclude=outputs --exclude=allure-results -cf - . | tar -C /workspace -xf -
cd /workspace
date -u > /results/snapshot-complete.txt
{ uname -a; cat /etc/os-release; java -version; } > /results/environment.txt 2>&1
xvfb-run --auto-servernum --server-args='-screen 0 1920x1080x24 -dpi 96 -nolisten tcp -ac' ./gradlew --no-daemon --no-watch-fs --max-workers=2 --no-configuration-cache :plugin:visualTest --console=plain --stacktrace 2>&1 | tee /results/gradle.log
