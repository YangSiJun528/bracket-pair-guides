# BMF export caller integration review

Read-only static review on frozenf65. No source/config/guide edits, builds, JVM, tests, performance execution or commit. Only this note changed. Runtime confirmation remains main's work after matrix completes.

## Confirmed configuration conflict

`benchmarks/build.gradle.kts:23–25` reads the root Gradle property benchmarkJob and permits only analyzeCold/analyzeReuse/visibleQuery/repair/cancelledAttempt. Explicit `-PbenchmarkJob=all` is rejected by its configuration-time require. This applies even when the requested root task is only exportBenchmarkMetrics: the benchmark subproject is part of the normal build.

`build-logic/.../ModuleBoundariesPlugin.groovy:48–51` registers that root task with `job.set(root.providers.gradleProperty("benchmarkJob").orElse("all"))`. BenchmarkReportTask.convert permits all as its internal report-selection value and requires all40 method/distribution/size cases. Therefore omitting the Gradle property selects the intended all-report mode without presenting an invalid benchmark selection to the subproject. No change to job validation, coverage or thresholds is needed.

## All relevant caller locations

Static rg search covered benchmarks, root/module builds, build-logic, .github, tools, docs and hooks (excluding build/cache/generated trees), plus current performance wrappers and reviewed proposal.

| Location | Finding | Minimal proposal |
|---|---|---|
| benchmarks/guide_bencher.md:11 | Example passes invalid -PbenchmarkJob=all | Remove that one argument |
| .github/workflows/benchmark-jobs.yml:271 | Real coverage job passes the same invalid argument after joining all5 raw job files | Remove that one argument; preserve input/output paths, jq/cmp, conditions and every gate |
| outputs/issue-97/redesign/benchmark-workflow.proposal.yaml.txt:272 | Historical reviewed proposal contains the same original command | Preserve as review record, or append an explicit correction note; do not silently rewrite approval history |
| ModuleBoundariesPlugin.groovy:51 | Task defaults internal report job to all | Keep unchanged |
| BenchmarkReportTask.groovy:30–35 | Internal all is legal and expands exact40-case set | Keep unchanged |
| benchmarks/guide_benchmarking.md:10 | Explicit benchmarkJob=analyzeCold is one valid measured job | Keep unchanged |
| benchmarks/runner + performance/*.py | No exportBenchmarkMetrics or benchmarkJob=all caller found | No change indicated |

The workflow bug affects both configured Bencher and local/fork coverage paths because both reach the common coverage job. It would fail after measurement, before complete/disjoint coverage can be accepted. Passing policy unit tests exercise the converter, so they do not prove this Gradle command's configuration succeeds. This is a genuine integration gap rather than a measured-data or threshold problem.

## Minimal proposed commands (not executed)

Guide and workflow should invoke:

```shell
./gradlew exportBenchmarkMetrics \
  -PbenchmarkResults=results/all-jmh.json -PbenchmarkBmf=results/all-bmf.json
```

For one-job8-case exports the property remains an actual job name, e.g. -PbenchmarkJob=repair. For an all40-case export omit it; do not add all to benchmark execution's whitelist merely to repair a reporting example.

Main can verify the corrected root invocation against a preserved complete40-case formal raw array after matrix source freeze is released. This requires no new measurements: concatenate one existing full run's5 jobs without altering rows, run the export command, compare exact benchmark identities/latency/B-op against the preserved raw/standard jq BMF, and retain actual command exit/output. Missing/duplicate/malformed40-case reports must still be rejected by unchanged policy. A configuration-only help invocation would not prove actual BMF conversion or values.

Any installed workflow correction should be recorded as this narrow local caller fix within existing approved local CI scope: no permissions, upload destination, PR condition, remote execution, Bencher lookup or seed policy changes. No correction has been installed by this review agent. Note frozen.

## Applied minimal local correction

After matrix03 completed, main explicitly authorized the narrow correction. Removed only `-PbenchmarkJob=all` from exportBenchmarkMetrics in benchmarks/guide_bencher.md and .github/workflows/benchmark-jobs.yml. Root default all, execution whitelist, conversion/threshold/permissions and all other workflow behavior are unchanged. Historical proposal was not edited. Actual Gradle40-case export and jq value comparison remain main-only pending validation; this note does not claim execution success. No builds/JVM/tests/measurements/commit were run by the editing agent. Files frozen.

## Actual integration follow-up and provider correction

Local commit8d853ea08d92150c127efe43d807771542ef84d6 contains the narrow caller/provider correction and two TestKit tests; production remains f65. integration01 originally reached conversion but failed configuration-cache serialization because both file-provider maps captured root Project. That original failure is preserved. The correction uses projectDirectory.file(Provider) for both paths, retaining lazy relative/absolute resolution and the unchanged cache policy.

Main executed bmf-fix-validation-01: positive11 tests pass (conversion9 plus actual-registration/cache/path2). In an isolated counterfactual restoring only the original two provider expressions, both new integration tests fail with the expected DefaultProject configuration-cache storage problem. No main worktree mutation was used for that counterfactual.

bmf-integration-02/summary.json records actual root exports of preserved historical pure01 input: each side40cases/80metrics, exact jq identity/value parity, configuration-cache store/reuse and task rerun after output deletion. This supersedes the earlier pending actual-export statement above; no new samples or final performance pass are claimed. fingerprint07 confirms eight semantic outcomes with candidate79518a18… and unchanged baseline0d2169b3….

final-check10 is running under default configuration-cache policy (expected184 plus standalonepure51, not preclaimed PASS); Qodana08 and final ZIP equality confirmation remain pending. Driver13/matrix03 are existing productionf65 evidence, reusable only after exact release hash equality is established. Final pure02/SDK02 and their raw-data BMF conversion/performance assessment remain pending. Only this evidence note and the three delegated reports changed in this update; no source/build/JVM/format/commit executed by the agent. Frozen.

## Final checkpoint validation update

Read final-check-10/summary.json and existing-validation-artifact-binding.json. At8d853ea, productionf65 unchanged: root184(older182 retained plus new2) PASS; buildlogic52/minimum35/current35 XML fresh. Standalone SDK-free51 fresh rerun PASS. Release ZIP remains exactly3af98835450aaea492c213841fefc8c386c3435fabb6f2352d6484a0572ddc78, binding existing Driver13/matrix03 evidence without rerunning those tools on8d.

Default configuration cache remained enabled. Root/pure cache entries were discarded with184/3 cache problems from existing notCompatibleWithConfigurationCache resolved-task audit tasks; whole-check cache reuse is not established. The BMF exporter store/reuse/reexecution after output deletion remains separately proven by integration02 and its tests. Qodana08 is running. Formalpure02/SDK02 and new-raw BMF/final performance assessment remain not-run. This update supersedes the earlier pending final-check10/hash statements only. Agent ran no builds/tests/JVM/measurements/format/commit. Frozen.

## Formal campaign02 execution update

Qodana08 summary now confirms actualexit0/findings0/sourceUnchanged/uploadfalse. sdk-final-smoke09 main review confirms16setup PASS. Frozen pure02 completed60invocations and80metric aggregation(60anyIncrease,34allSixIncrease,7over20); mainperformancePass=false. SDK02 completed96invocations and774metric aggregation(511anyIncrease,64allSixIncrease,40over20); detailed main judgment and independent audit remain pending. Full source/compiled/JAR freeze is retained.

Main is preparing actual exporter/jq validation on the final rawpair01, selected in the plan before measurement. This is still pending and must not inherit integration02's older-raw conversion pass. No new trial, altered measurement/threshold or performance pass is claimed. Agent only updated delegated evidence documents; no code/build/JVM/measurement/commit executed. Frozen.

## Final actual raw export and closed local execution scope

bmf-final-01/summary.json confirms actual root exporter on preregistered finalpure02pair01: eachside40cases/80metrics,exactjqnames/values,cache store/reuse,outputdeletion then actualtaskreexecution/identicalbytes;sourceUnchanged,remoteUploadfalse. No measured samples were chosen after seeing results; statistical assessment uses all6pairs. This supersedes earlier pending-finalraw statements in this historical note.

Pure02mainreview and independent review both retain performancePassfalse; SDK02mainassessment now also performancePassfalse. SDK execution-main-review confirms96freshpassingXML/zeroinputproducerreexecution/allrawhashes. All planned local execution/aggregation/assessment is complete; converter/cache correctness does not establish parity or waive7pure/40SDKflags. RemoteBencherhistory/CIwereoutsideuserauthorizedscope and notrun, never markedpassed. ImplementationHEAD8d853ea/productionf65 and exactrelease3af988…78 remain preserved. Verification target is implementation HEAD8d853ea/productionf65; staged local commit history is available in git log. Agent updated only delegated outputs and ran no code/build/JVM/tests/measurement/commit. Frozen.
