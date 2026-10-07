# Independent current verifier matrix review

Reviewed runs/20261007T085741209814Z summary, frozen matrix, all13 target JSON records, actual verifier logs and verification-verdict.txt reports. No build, Docker run or measurement executed.

All13 frozen targets passed. selected/frozen/strictPassed lists match; no target remains pending. Each target's real Gradle verifier invocation exits0, logs BUILD SUCCESSFUL and its actual product/version/build, and has one report verdict exactly Compatible. Product identities match the requested release version or requested EAP build. Minimum IC2024.1.7/build241.19416.15 is covered along with IC2024.2.6,2024.3.7.1,2025.1.7.2,2025.2.6.3 and IU2025.3,2025.3.6.1,2026.1,2026.1.5,2026.2,2026.2.3,263.4732.28,263.6259.32.

The same unchanged8 strict failure levels are recorded for every target and checked by the frozen init script: COMPATIBILITY_PROBLEMS, DEPRECATED_API_USAGES, EXPERIMENTAL_API_USAGES, INTERNAL_API_USAGES, OVERRIDE_ONLY_API_USAGES, NON_EXTENDABLE_API_USAGES, MISSING_DEPENDENCIES, INVALID_PLUGIN. No target selection or failure level was weakened.

Every target records identical before/after archive SHA256 f07ede40f0dce7d34bf6a092780c9349437f50664ed29510c5a640ed30ef3777, matching the matrix summary and deterministic packaging archive identity. Successful verifier evidence is for this frozen archive, rather than historical sibling-worktree distributions.

Latest-runtime fixture evidence is independently confirmed in runtime-fixtures/IU-263.6259.32: actual product IU2026.3/build263.6259.32; runtimeFixtures exits0;64 JUnit XML files contain411 cases,0 failures/errors/skips. The exact (classname,test name) multiset matches all411 frozen default-fixture cases. The log's actual Gradle Test Executor process launches the selected IDEA distribution's bundled jbr/Contents/Home/bin/java and passes -Didea.home.path to that exact263.6259.32 distribution. Thus this establishes real latest-runtime execution rather than merely configured target properties.

These results establish strict binary/API compatibility across the recorded13-target matrix and correctness assertions from the411 fixtures on the selected latest EAP runtime. They do not establish Driver screenshot behavior on every target, compatibility with every product/third-party plugin, or absence of all threading/algorithm bugs. Driver and performance checks retain their separate current evidence requirements.
