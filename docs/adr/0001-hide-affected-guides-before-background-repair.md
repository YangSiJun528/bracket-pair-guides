# Hide affected guides before background repair

The user selected synchronous hiding of affected guide geometry followed by
immediate background correction, so editor UI callbacks perform no indentation
analysis. The former bounded UI scan measured p95 at or below 0.105 ms across
the tested frozen corpora in three warmed IDE 241 fixture runs. The measurement
covered one fallback call after a real edit and excluded the edit and session,
native, and full-analysis work; that evidence did not establish a general latency
guarantee, and the choice was made to
remove UI analysis rather than to claim the old scan was slow. Repair preserves
the 256-line and 32,768-consumed-character limits and runs separately from the
75 ms secondary full-analysis debounce; the guide may remain hidden until a
valid repair or authoritative snapshot arrives. The alternatives were keeping
the bounded synchronous scan or maintaining incremental repair state.

On 2026-10-09, Tab/Shift+Tab QA identified unnecessary hiding when a body's
indentation changed strictly to the right of the existing guide column. A bounded
horizontal-whitespace edit can now preserve geometry after proving its minimum
column and earliest anchor unchanged. This refines which edits affect geometry;
it does not delay hiding of unproven or changed geometry. Event classification
and SDK logical-coordinate lookup replace no background indentation calculation.
The [presentation policy](../explanation_editor_presentation_policy.md) specifies
the proof and conservative admission bounds. Content validity and cancellation
still advance for every edit, even while this tracked geometry remains visible.
