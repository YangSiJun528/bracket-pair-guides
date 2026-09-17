#!/bin/bash
set -euo pipefail

export CI=true
export VISUAL_TEST_ENVIRONMENT=ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1

collect_results() {
  status=$?
  trap - EXIT
  set +e
  printf '%s\n' "$status" > /results/exit-code.txt
  for mapping in \
    'plugin/build/visual-test-artifacts:visual-test-artifacts' \
    'plugin/build/test-results/visualTest:junit-results' \
    'plugin/build/reports/tests/visualTest:test-report'; do
    source_dir="${mapping%%:*}"
    destination="${mapping#*:}"
    if [[ -d "$source_dir" ]]; then
      cp -a "$source_dir" "/results/$destination"
    fi
  done
  for test_dir in plugin/out/perf-startup/tests/*/*; do
    [[ -d "$test_dir" ]] || continue
    relative_dir="${test_dir#plugin/out/perf-startup/tests/}"
    for diagnostic in log reports snapshots; do
      if [[ -d "$test_dir/$diagnostic" ]]; then
        mkdir -p "/results/starter-diagnostics/$relative_dir"
        cp -a "$test_dir/$diagnostic" "/results/starter-diagnostics/$relative_dir/"
      fi
    done
  done
  exit "$status"
}
trap collect_results EXIT

# Use a fresh Linux workspace, including uncommitted source changes. Reusing
# macOS Gradle outputs or IDE distributions would mix rendering environments.
tar -C /source \
  --exclude=.git --exclude=.gradle --exclude=.idea --exclude=.kotlin \
  --exclude=.intellijPlatform --exclude=.qodana --exclude=build \
  --exclude=out --exclude=outputs --exclude=allure-results \
  -cf - . | tar -C /workspace -xf -

# Starter has its own IDE download cache outside GRADLE_USER_HOME. Preserve
# installers and extracted IDEs, but keep test config, projects and logs fresh.
mkdir -p /cache/starter/installers /cache/starter/cache /workspace/plugin/out/perf-startup
ln -s /cache/starter/installers /workspace/plugin/out/perf-startup/installers
ln -s /cache/starter/cache /workspace/plugin/out/perf-startup/cache

{
  uname -a
  cat /etc/os-release
  java -version
} > /results/environment.txt 2>&1

xvfb-run --auto-servernum \
  --server-args="-screen 0 1920x1080x24 -dpi 96 -nolisten tcp -ac" \
  ./gradlew --no-daemon --max-workers=2 visualTest --stacktrace \
  2>&1 | tee /results/visual-test-gradle.log
