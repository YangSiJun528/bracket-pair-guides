# Issue 97 implementation record

## Objective

Enforce production module access using Gradle compiler classpaths: platform-neutral model/core, UI with read-only results and request/cancel interfaces, runtime owning execution, and plugin assembly without direct core access. Preserve PR 96 behavior, ADRs and all verification thresholds.

## Starting state

- PR 96 checked via GitHub on 2026-10-07 (Asia/Seoul): OPEN, latest head `84d1a43141b1933c81e027ef01d1579fc4679e6e`.
- Base: that exact head; branch `codex/issue-97-module-isolation` in isolated Codex worktree.
- Original main checkout was clean and remains untouched.
- Issues 93/97 and PR 96 read. No push, PR creation, merge, or external upload authorized.

## Coordination

- Main: design decisions, integration, commits, serialized builds and measurements.
- core_design: read-only model/core investigation; output core-investigation.md.
- runtime_design: read-only UI/runtime investigation; output runtime-investigation.md.
- build_validation: read-only build/verification investigation; output build-investigation.md.
- Workers requested as gpt-6.1-sol / medium. No concurrent Gradle/build/performance jobs.

## Current status

Reused committed five-module implementation a8e0bc1 by fast-forward, after checking its merge base is exactly PR96 head. The sibling worktree has uncommitted user changes; none were modified or imported. Independent reviews completed. Fixed lost primitive range validation in core; strengthening owner-positive compilation controls and source-path bypass audit.

Initial SDK-free clean check passed using restored test cache (not fresh execution). Fresh full check failed correctly after new source-archive audit classified SDK template resources too broadly; correction in progress. Independent uncached pure/IDE tests running.

Fresh baseline archive: see baseline-location.json. Offline baseline preparation failed missing per-worktree IntelliJ metadata; online resolution then succeeded without source changes.

Docker started for requested Linux Driver. No measurements started. Previous committed validation summary/reference describe historical sibling results only, including unresolved latency signals; current results will be separately reported.

## Validation evidence

Pending. Previously reported PR 96 results are context, not issue 97 verification.


## Current validation update

- Fresh uncached model/core/plugin tests: 518 passed, zero failures/errors/skips (6 + 101 + 411). `current-check/` retains XML; `tests-current.log` command output.
- Local commit `0b452df`: restore nonnegative/ordered range validation lost when TextRange was replaced by primitive offsets.
- Bencher tooling: 52 passed; visual reporter: 5 passed.
- Guard hardening: Java-positive implementation owner controls; explicit empty production Java sourcepath; typed source/bootstrap/processor paths audited; actual source-only archive control. SDK companion sources carry audited bytecode and cannot be recompiled through the empty sourcepath.
- Historical reference_validation.md and validation-summary.json are imported prior-run evidence, not this worktree's current results. Current report will use current-* artifacts.
