# Current verification runner review

Prepared for the current isolated worktree. No Gradle, IDE, Docker, image pull, or verifier was executed by this review. Python compilation and bash syntax checks passed.

Run serially after main preserves passing plugin XML in `outputs/issue-97/current-check/plugin` and builds the final archive:

```sh
./gradlew --no-daemon --max-workers=2 --no-configuration-cache --init-script outputs/issue-97/current-verification/serial-verifier.init.gradle :plugin:recordSerialVerifierMatrix
python3 outputs/issue-97/current-verification/run-final-serial-verifiers.py
python3 outputs/issue-97/current-verification/run-final-serial-verifiers.py --execute --cleanup-created-ides
```

The first command acquires current configured recommended metadata and freezes it together with the four explicit declarations. Both freeze and execution enforce all 13 targets and all eight strict failure levels. Existing matrix files are never overwritten; review a changed target count instead of truncating the matrix. Runtime fixture execution remains IU-263.6259.32 and compares exact testcase identities, counts, XML file counts, zero skips/failures/errors, actual selected IDE and JVM, and unchanged archive hash.

Expected plugin fixture count defaults to 411. If current check legitimately contains additional/changed tests, supply its exact total with `--expected-fixture-tests N`; the runner still requires exact full current testcase identity equality on IU263. A hardcoded historical count no longer prevents testing the complete current suite. No test filtering is introduced.

Cleanup remains opt-in and only after successful strict verification plus runtime fixtures where required. It requires exclusive Gradle/IDE acquisition ownership. Only exact target installer/transform artifacts absent immediately before acquisition qualify; pre-existing entries and unsuccessful/uncertain artifacts remain. No broad cache cleanup is performed.

Qodana image `jetbrains/qodana-jvm-community:2026.2` is currently absent according to main's inventory. It requires explicit acquisition before the local runner:

```sh
docker pull --platform linux/amd64 jetbrains/qodana-jvm-community:2026.2
bash outputs/issue-97/current-verification/run_qodana_local.sh
```

The runner itself uses `--pull never`, an empty QODANA_TOKEN, and local results; it does not upload. It snapshots the current worktree to `current-verification/qodana/source.*` and writes evidence to `qodana/results.*`. The default mounted Gradle cache is the existing Linux visual-test cache under the primary repository; `QODANA_GRADLE_CACHE` can select another existing Linux cache. Do not point it at macOS `~/.gradle`. The Docker process may update the mounted cache, so serialize it with other consumers.

Current disk inspection reported 26 GiB free. Image pull/extracted IDE/runtime acquisition can consume significant space; serial runner checks a 9 GiB free floor before each target. It stops and preserves evidence when below the floor. It does not remove pre-existing shared caches to recover space.
