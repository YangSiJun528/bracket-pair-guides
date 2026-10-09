# Independent final archive matrix review —completed2026-10-07 UTC

Run: runs/20261007T112724798906Z. Independent read-only review of frozen input hashes, all13 original target records/logs/verdict reports, final summary and latest-runtime XML/process logs. No build, Docker or measurement launched by this reviewer.

## Final result

All13 targets strictly pass for final ZIP SHA25683d4c0a23cdd245b933fb6c663bdddb944a29d1342d01f0d73e72df4631c0da8. summary.json exists and allTargetsPassed/runtimeFixturePassed are true; frozen/selected/strictPassed/selectedAllRequiredPassed lists all match the full13-target matrix. All pending lists are empty. The actual current distribution ZIP still hashes to this same value at final review. This is a new completed run for final83d4c0a2 bytes; initial f07ede40 matrix results are not substituted.

For each target, independently inspected original JSON, actual verify log and verification-verdict.txt: verifyExit0, BUILD SUCCESSFUL, frozen target actual identity logged, one verdict exactly Compatible, before/after ZIP SHA equal final83d4c0a2. Product code and actual release version or EAP build match the requested target. The final13th IU263.4732.28 record is finished/strictPass=true, exit0, with matching archive hashes and actual Compatible report.

The targets are IC2025.2.6.3,2025.1.7.2,2024.3.7.1,2024.2.6,2024.1.7 and IU263.6259.32,2026.2.3,2026.1.5,2025.3.6.1,2025.3,2026.1,2026.2,263.4732.28. The same8 strict failure levels remain enforced for every target: COMPATIBILITY_PROBLEMS, DEPRECATED_API_USAGES, EXPERIMENTAL_API_USAGES, INTERNAL_API_USAGES, OVERRIDE_ONLY_API_USAGES, NON_EXTENDABLE_API_USAGES, MISSING_DEPENDENCIES, INVALID_PLUGIN. No target or failure condition was weakened.

## Input and cleanup identity

Verified all70 input-hashes entries against actual frozen inputs: final ZIP, runner/init scripts, matrix metadata, fixture summary and64 baseline fixture XML files. This is a frozen execution-input record, not a complete production source archive; source provenance and exact compiled-versus-packed class bytes remain in final-check evidence.

Cleanup records remove20 artifacts, consisting only of the10 newly acquired targets' selected installer/transform pairs. Each removal is within its recorded eligibleCreatedArtifacts and exact selectedTarget acquisition paths. Removed transforms were absent from the initial pre-existing-transform inventory; removed installers were absent from each preExistingTargetInstallers list. The3 pre-existing cached IDE targets removed0 artifacts. No broad shared cache or existing user worktree deletion is recorded.

## Actual latest-runtime execution

Independently parsed all64 runtime-fixtures/IU263.6259.32 XML files:411 cases pass with0 failures/errors/skips, and the exact (classname,test name) multiset matches411 frozen final-check baseline cases. The actual fixture process exits0 and logs BUILD SUCCESSFUL.

Actual product is IU2026.3/build263.6259.32. The real Gradle Test Executor process launches the selected distribution's bundled jbr/Contents/Home/bin/java and passes -Didea.home.path to that same263.6259.32 distribution. Thus this is actual selected IDE/JBR execution rather than merely configured target properties.

## Scope

Earlier2/13,5/13 and12/13 observations were historical partial snapshots before completion; this review supersedes their pending status. Strict binary/API compatibility is established across this recorded13-target matrix for the final archive, and411 latest-runtime fixture assertions pass. These results do not prove Driver behavior on every IDE, support for every product/third-party plugin, performance equivalence, or absence of all threading/cancellation/algorithm defects. Final Driver and performance retain their separate recorded evidence and stated limitations.
