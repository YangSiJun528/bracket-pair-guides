# Reviewed visual contracts

These twelve images were captured from unchanged production `f59e452` on
IntelliJ IDEA 2026.2.3 (262.10968.63), Linux x86-64, Xvfb DPI 96,
Darcula/New UI, JetBrains Mono 14 and scale 1. The fixture has an explicit Java
source root; no project JDK is required. IDE Starter prepares an existing profile
so first-session trial activation cannot replace the editor or change plugins.

Every image was visually reviewed against `docs/reference_visual_testing.md`.
The source capture also passed real Settings Apply, focus/visibility transitions,
public native-conflict notification, caret and tab round-trips, and synchronous
indentation retention followed by repaired column-10 geometry.

Source and review evidence:
`outputs/platform-253-evaluation-20261022/reference-main/` and
`outputs/platform-253-evaluation-20261022/baseline-review.json`.
All twelve freshly captured PNGs are also byte-identical to the former 2024.2.6
oracles; only their environment directory changed. The former paths remain in Git history. This environment migration
uses unchanged production as its source; it does not approve candidate behaviour
by recording over a failing comparison. The candidate passed exact comparison for all twelve images and the interaction
contracts. Replacing only `paintOutline` with a no-op reached the pixel assertion
and failed nine guide-bearing scenarios as expected. Evidence is recorded in
`visual-comparison.json`, `validation.json`, and `mutation-no-outline/` under the
same evaluation output directory.
