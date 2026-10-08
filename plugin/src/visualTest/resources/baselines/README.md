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
