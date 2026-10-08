# Bracket Pair Guides

The plugin recognizes language-defined bracket pairs and presents the pair surrounding the primary caret. These terms distinguish calculation results, desired presentation, and the evidence needed to display them.

## Brackets and geometry

**Bracket token**:
A syntax token classified by its language's brace rules as an opener, closer, or symmetric delimiter. A character that looks like a bracket is not sufficient evidence.
_Avoid_: bracket character

**Bracket pair**:
A recognized opening and closing token with their source ranges, nesting depth, and document lines. Unmatched tokens are not pairs.
_Avoid_: bracket match, bracket scope

**Active pair**:
The innermost recognized pair containing the primary caret, strictly after its opening offset and before the end of its closing token.
_Avoid_: selected pair, focused pair

**Guide geometry**:
The visual column and anchor line used to draw a guide for a bracket pair. Geometry describes placement; it does not imply that the guide is currently visible.
_Avoid_: guide analysis, guide state

**Anchor line**:
The earliest eligible line attaining the chosen indentation column. An approximate repair may use a provisional anchor until authoritative analysis arrives.
_Avoid_: minimum line

**Bracket view**:
An immutable result that answers active-pair, guide-geometry, and bounded token queries. It contains no decision about whether a particular editor should display those answers.
_Avoid_: stamped snapshot, editor snapshot

**Token window**:
A bounded selection of recognized pair endpoints near a requested source range and focus offset. Capping limits the selection without making it a complete document result.
_Avoid_: token graph, viewport index

## Analysis and presentation

**Analysis coverage**:
The requested or available facets of bracket results: tokens, active-pair queries, and guide geometry. Guide geometry requires active-pair coverage.
_Avoid_: presentation coverage

**Guide demand**:
One editor's complete desired work state, including coverage, visibility, language selection, and any repair interest. It describes what is wanted rather than a sequence of worker operations.
_Avoid_: calculation command, work queue

**Accepted result**:
A calculation result whose source, desired coverage, editor lifetime, and successful display application have been approved for the current editor.
_Avoid_: latest result, cached result

**Provisional guide**:
Guide geometry retained or repaired after an edit while authoritative analysis is pending. Its use depends on tracked endpoints and current presentation ownership.
_Avoid_: authoritative snapshot, exact analysis

**Guide repair**:
A bounded recalculation of guide geometry for an already tracked pair. Repair does not establish a new document-wide pairing result.
_Avoid_: partial full analysis

**Capacity refusal**:
A result indicating that completing a requested facet would exceed its admitted capacity. Pairing refusal yields no authoritative prefix; guide refusal can preserve exact lower facets.
_Avoid_: truncated success

**Source identity**:
The captured document and language-source facts against which pending work and results are checked. It is distinct from the order of requests and from presentation activity.
_Avoid_: request generation, reuse revision

**Native conflict evidence**:
A current proof that the IDE's own brace or navigation emphasis conflicts with a displayed plugin guide. A painted marker alone is not sufficient evidence.
_Avoid_: native warning, marker guess
