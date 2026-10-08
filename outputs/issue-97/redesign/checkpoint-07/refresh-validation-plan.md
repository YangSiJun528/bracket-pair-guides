# Refresh validation after the scalar-prefix change

Main-agent execution reference only; none of the commands below was executed to prepare this note. Existing evidence remains retained under its original names. The scalar-prefix core/runtime/benchmark sources differ from the source revision validated by listener-lifetime-01/final-check-06, so those results must not be relabeled as final-source results.

Use the redesign worktree as cwd. Allocate fresh directories under `outputs/issue-97/redesign/checkpoint-07/` (or new numbered evidence namespaces); retain exact command arrays, final source revision and source SHA inventory, start/end times, actual exits and copied raw evidence. Run serially: no competing Gradle, Qodana, Driver, verifier or measurements. Format/fix before the final source freeze, then record any later modification as invalidating source-specific evidence.

## 1. SDK-free build and root correctness

```sh
./gradlew --no-daemon --no-parallel --max-workers=2 --no-configuration-cache \
  -p tools/pure-build check :analysis-model:build :analysis-core:build --console=plain
./gradlew --no-daemon --no-parallel --max-workers=2 --no-configuration-cache \
  check :plugin:buildPlugin :plugin:buildVisualTestPlugin \
  :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure --console=plain
```

Mirror final-check-06 command/source/summary/log/XML collection into fresh `pure/` and `root/` directories. The independent pure entrypoint writes XML/classes/jars under `tools/pure-build/build/analysis-model` and `tools/pure-build/build/analysis-core`, **not** their ordinary module build directories. Copy those actual pure XML sets and record SDK-free settings/compiler-input policy outcomes and exact source parity. Do not accidentally count the root suite's older XML as the pure run.

Root check must retain Spotless, all five module checks, actual production/UI-test classpath guards, included build-logic check (all real Kotlin/Java owner-positive/forbidden-negative and dependency/source/output/friend/compiler-plugin contamination cases), JMH build and packaging guard. Build-logic test inputs now include the actual copied source/configuration tree; scalar-prefix changes should invalidate TestKit's previous fingerprint. Inspect task outcomes and fresh XML rather than assuming task inclusion proves execution. Preserve all existing cases and add the new scalar-route contract; do not hardcode the older total134 or discard additional cases. Any up-to-date reuse must be identified accurately.

Recheck packaging against actual selected owner archives/instrumented compiled outputs, strict five-owner library allowlist, exact class bytes, resources/descriptor/icons/licenses, and actual task graph. Derive inventories from current owners: **451 was an old observed class count, not an invariant or acceptance constant**. Record the new release ZIP SHA256; old verifier/Driver archive claims apply only to their old bytes.

## 2. Actual minimum/current IDE fixtures

```sh
./gradlew --no-daemon --no-parallel --max-workers=2 --no-configuration-cache \
  :plugin:minimumSdkTests :plugin:currentSdkTests \
  :plugin:buildPlugin :plugin:buildVisualTestPlugin \
  :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure --console=plain
```

Use listener-lifetime-01 as the evidence-collection model, without adding spotlessApply after freeze. Copy both XML directories immediately into fresh `sdk/minimumSdkTests` and `sdk/currentSdkTests`. Verify the same complete case identities, zero failure/error/skip, actual loaded IC-241.19416.15/IU-263.6259.32 and actual selected JBR/runtime origins. Existing29 identities remain required unless a reviewed new contract adds cases; do not substitute configuration values for actual identity-test output. Include listener lifetime, read thread/access, cancellation, stale acceptance, repair restoration, disposal and presentation cases. New core binaries are consumed by both SDK tasks, so both need fresh evidence.

## 3. Qodana and Driver

```sh
bash outputs/issue-97/redesign/qodana/run_local.sh
```

The script makes fresh isolated source/results directories automatically. Collect command/image ID/source inventory/run log/SARIF/exit into a new Qodana evidence directory, using run-04 as the model. Retain failThreshold0 and no new exclusions/suppressions to hide regressions. Verify zero actual SARIF problems and the final frozen source identity.

For Driver, copy compare-12/run.sh unchanged to a fresh compare directory. Reuse compare-12/command.json's local Docker command, replacing only container name, host `/results` directory and source revision/evidence namespace. Preserve local image ID, `--pull never`, Linux/amd64, read-only source mount, the separate Linux Gradle cache, Xvfb1920×1080/96dpi, environment pin, and existing reviewed oracles. No capture/record mode or new baseline acceptance is required by this computation change.

Collect actual exit, XML, screenshot comparison/verification.json, all12 expected PNG comparisons, source-before/after equivalence and release-versus-visual-bridge production equivalence using the same checks represented in compare-12/{verification,production-equivalence,host-driver-plugin-jar-differences}.json. Absence of an error log is not evidence of passing images. Do not waive geometry/settings/native/visibility/focus cases or add pixel tolerance. Driver must use new production class bytes, not merely an old visual ZIP.

## 4. Compatibility matrix on the new release ZIP

```sh
./gradlew --no-daemon --no-parallel --max-workers=2 --no-configuration-cache \
  --init-script outputs/issue-97/redesign/compatibility/serial-verifier.init.gradle \
  -PverifierMatrixPath=outputs/issue-97/redesign/checkpoint-07/compatibility/matrix.json \
  :plugin:recordSerialVerifierMatrix
python3 outputs/issue-97/redesign/compatibility/run_serial_verifiers.py \
  --matrix outputs/issue-97/redesign/checkpoint-07/compatibility/matrix.json \
  --testcases outputs/issue-97/redesign/checkpoint-07/compatibility/testcases.json --freeze-testcases
python3 outputs/issue-97/redesign/compatibility/run_serial_verifiers.py \
  --matrix outputs/issue-97/redesign/checkpoint-07/compatibility/matrix.json \
  --testcases outputs/issue-97/redesign/checkpoint-07/compatibility/testcases.json
```

Review the fresh plan, then run the same invocation with `--execute --cleanup-created-ides` as serial-command-02. Preserve all eight strict failure levels and all fresh recommended+explicit targets (normally13; review rather than silently dropping a changed recommendation). Retain new run-directory plan/input hashes, per-target verifier reports/verdicts, runtime-fixture identities and final summary. Every target must use the **same new ZIP SHA**, pending targets must be empty and process exit must be0. Cleanup remains exact run-owned acquisitions only; no user/shared/pre-existing cache cleanup. Matrix source/ZIP changes require a fresh matrix, never overwriting matrix-02 or relabeling its old13 passes.

## 5. Measurement prerequisites and fresh formal campaigns

After all correctness consumers finish, reproduce sdk-final-smoke-07's16 exact command templates (eight workloads × two sides) in fresh directories. Keep its isolated descriptor mode and original bootstrap/JBR/settings; change output and candidate source revision only, preserving smoke counts. Preserve raw JSONL/XML/descriptor and resource overlays, loaded origins/harness class SHA, corpus equality and no unowned attachments. Actual release descriptor remains unchanged; measured-only descriptors suppress only the approved startup/pass registrations. These small-count runs are setup checks, not performance passes.

The new required initialPrefix API changes candidate adapter and compiled jars; rerun all eight semantic fingerprints on the final selected JMH jars and write fresh fingerprint evidence, retaining fingerprint-01/02. Baseline production remains072533f; additive shared SDK9 files plus ComparisonHost must stay byte-identical across sides. Build/warm measurement producers last, including test instrumentation and measured descriptor/resource overlays; ordinary SDK checks can otherwise change those outputs. Freeze full source, candidate/baseline jars, selected compiled inputs and dynamic metadata-only top-level harness class proof.

```sh
python3 outputs/issue-97/redesign/performance/run_local_comparison.py \
  --phase pure --output outputs/issue-97/redesign/performance/NEW-PURE \
  --fingerprint-evidence outputs/issue-97/redesign/NEW-FINGERPRINT.json --plan-only
python3 outputs/issue-97/redesign/performance/run_local_comparison.py \
  --phase pure --output outputs/issue-97/redesign/performance/NEW-PURE \
  --fingerprint-evidence outputs/issue-97/redesign/NEW-FINGERPRINT.json \
  --execute --acknowledge-serial-idle
```

Repeat with `--phase sdk` and a separate fresh output directory. Pure-01 is completed execution evidence with known regressions, **not** a failed-wrapper status: do not pass it to `--supersedes`, which accepts only failed campaigns. Reference pure-01 explicitly in the investigation/final report while preserving every raw sample.

Keep six AB/BA pairs, five exact JMH jobs/forty cases,2forks/1thread/2×1s warm/3×1s measure/gc/2GiB/240s, and all eight SDK workloads with100/30/30 and existing deadline budgets. No selective retry, pooling, budget/threshold change or concurrent verification. Stop on setup/failure/changed producer inputs and preserve all raw evidence. SDK has not yet had a formal campaign; do not imply otherwise. Generate fresh reports only for completed campaigns; report all paired deltas/ratios, added allocation, censoring, overlap misses and previous regressions. The20% investigation criterion and existing CI thresholds remain unchanged; the main agent makes the final judgment.
