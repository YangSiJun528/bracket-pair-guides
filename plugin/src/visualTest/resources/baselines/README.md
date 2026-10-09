# Reviewed visual contracts

These twelve images replace the removed legacy suite under the approved redesign.
They were captured and individually reviewed on IC 2024.2.6, Linux x86-64,
Xvfb DPI 96, Darcula/New UI, JetBrains Mono 14, scale 1, en_US/UTC.
The expected geometry and state are defined in `docs/reference_visual_testing.md`.

Review evidence: `outputs/issue-97/redesign/driver/capture-04/baseline-review.json`,
with the exact capture source manifest and image hashes. The capture also passed
Settings Apply, genuine focus and visibility transitions, notification display,
caret A/B/A pixel equality, and synchronous edit hiding followed by repair.
An independent exact comparison and rendering-removal mutation are required
before the redesign is considered verified. No tolerance or automatic acceptance
is permitted.

Three images (`native-visuals-unmanaged`, `native-highlight-suppressed`,
`edited-geometry`) were subsequently corrected to settled SDK state. The initial
native images retained identifier-usage fills from the previous caret location;
the edited image lacked a recalculated native indent-guide segment. Standard
project index readiness and daemon completion before/after stable captures now
prevent accepting those intermediate states. The exact pixel oracle and
30-second capture budget are unchanged.

Correction review: `outputs/issue-97/redesign/driver/settled-oracle-review/review.json`.
The three previous images remain in that directory's `previous-oracle/`.
The replacements come from unchanged baseline production `072533f`, using the
same twelve-scenario Driver suite; all twelve images matched the redesigned
production in every ARGB pixel and PNG byte. Cross-production evidence:
`outputs/issue-97/redesign/driver/baseline-counterfactual-01/cross-production-comparison.json`.
That diagnostic capture is not an old-golden comparison pass. Nine original
images were unchanged; the two native corrections each changed 2,218 pixels and
the edit correction changed 20. Fresh exact comparison and the paint-removal
mutation remain required after the deliberate correction.
