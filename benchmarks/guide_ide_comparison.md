# Record an IDE plugin comparison

This how-to records a black-box IntelliJ comparison of the baseline IDE,
Bracket Pair Guides, Rainbow Brackets, and Color Brackets. The checked-in
[0.0.1 result](ide-comparison/results/0.0.1/summary.md) is exploratory evidence,
not a performance claim or CI baseline.

## Prerequisites

- macOS on Apple silicon
- an unpacked IntelliJ IDEA Community build with the bundled
  `performanceTesting` plugin
- JBR `java`, `javac`, and `jar` from that IDEA build
- `zsh`, `jq`, `rg`, `unzip`, `shasum`, `awk`, and `sed`
- local distribution files for Bracket Pair Guides, Rainbow Brackets, and
  Color Brackets

Competitor binaries are inputs and must not be committed. Likewise, do not
commit IDE sandboxes, screenshots, class files, JFR recordings, or heap dumps.

## Build the deterministic inputs

Compile and run
[`GenerateHarness.java`](ide-comparison/scenario/GenerateHarness.java) with the
selected IDEA JBR. Pass one empty temporary directory as its only argument. It
generates:

- a 4,562-line Java fixture with SHA-256
  `83347637c1ebad56043649d6bd9083ec3c910c0119a476d61d0f5a1be7055887`;
- one activation playback script; and
- one primary playback script implementing the phases in
  [`protocol.env`](ide-comparison/config/protocol.env).

Compile the two Java sources under `ide-comparison/helper/src` against the IDEA
libraries and bundled `performanceTesting.jar`, add the helper `plugin.xml`, and
package them as an unpacked local plugin. It supplies exact heap and idle CPU
markers plus a Timer-only profiler backend; it does not start JFR or create a
heap dump.

## Prepare each case

Create one common warmed IDEA config/system template. Seed
[`editor.xml`](ide-comparison/config/editor.xml) before warming it so native
matched-brace foreground/background highlighting is disabled. Clone that
template into a fresh sandbox for every row, then install only:

- the measurement helper for `baseline`;
- the helper and Bracket Pair Guides for `bpg`;
- the helper and Rainbow Brackets for `rainbow`; or
- the helper and Color Brackets for `color`.

Use plugin defaults. Before measuring, open the generated fixture once per
plugin and visually verify that its intended output is present. Record the
plugin ID, version, distribution and descriptor-JAR hashes, screenshot hash,
IDE/JBR versions, JVM options, host, and feature configuration in the result
manifest. A screenshot hash is evidence of what was reviewed; it is not an
automated visual assertion.

## Run blocks

For a technical sanity check, run the first three orders in `protocol.env`.
Use ten blocks only for a separately designed and reviewed statistical study.
For each row, launch IDEA directly with the generated primary script and the
case-specific config, system, plugin, log, snapshot, and memory paths.

Validate all of the following before accepting a row:

- IDEA exited successfully;
- the exact target ID/version and measurement helper were loaded;
- native matched-brace highlighting was false before and after the run;
- plugin output had been visually verified, or the case was baseline;
- fixture, workload, helper, and plugin hashes matched the manifest;
- exactly one heap, idle CPU, near Timer, and far Timer value was present; and
- no JFR or heap-dump file was created.

Append every row to `raw-runs.csv`, including failures. If any row fails, finish
recording that attempt, exclude the entire four-variant block, and rerun all
four variants with a new attempt ID. Do not replace or delete failed rows.

## Summarize the record

After three complete blocks exist, run the following from that release's result
directory:

```shell
awk -F, \
  -v md=summary.md \
  -v aggregate=aggregate.csv \
  -v paired=paired.csv \
  -v audit=audit.csv \
  -v source_csv=raw-runs.csv \
  -f ../../scripts/summarize.awk \
  raw-runs.csv
```

The summary reports median `[min, max]`, paired BPG-versus-competitor
contrasts, invalid rows, valid rows excluded with an incomplete block, and
log-unclean rows. Review the generated record manually before committing it.

Interpret the metrics narrowly:

- heap is whole-IDE JVM used heap immediately after an observed full GC, not
  plugin-retained heap or RSS;
- idle CPU is whole-process CPU time, not plugin-only CPU or battery use; and
- caret timings cover synchronous `%goto` command completion, including the
  editor work completed before each command returns; they do not fence deferred
  visual updates and are not direct paint or user-perceived latency.

Three blocks are too few for a Marketplace or README performance ranking. The
0.0.1 snapshot is kept because it exposed both favorable and unfavorable
signals and documents why no cross-plugin performance claim was published.
