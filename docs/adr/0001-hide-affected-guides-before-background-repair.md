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

On 2026-10-21, the user authorized replacing affected-guide hiding with
provisional retention to reduce short visible gaps. A still-tracked pair keeps
its adjusted renderer until current repair or analysis replaces its position;
invalid endpoints and lost presentation ownership still clear immediately.
Presentation distinguishes visible geometry from repair interest without adding
indentation scans, input-specific state, or new caches. The trade-off is that an
old column may remain visible while calculation is pending, including a refused
bounded repair. The [current presentation policy](../explanation_editor_presentation_policy.md)
defines the replacement behavior.

Later on 2026-10-21, direct IDE testing found the mixed intermediate shape
uncomfortable: the old column remained while horizontal endpoints followed the
edited brackets. The user selected freezing the last complete visible drawing
until current geometry is ready. Presentation now retains one visible vector
outline, replays it during repair, and discards it on viewport or layout changes.
Tracking and repair authority remain independent of those provisional pixels.
This changes the intermediate shape, not the background calculation latency.
