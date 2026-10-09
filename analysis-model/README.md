# Analysis result model

This module defines the platform-free values shared by calculation and presentation. `model.result.BracketView` is the read-only result boundary: callers can find an active pair, obtain its guide geometry, and request a bounded primitive token window. It exposes no builder, concrete index, input capture, or calculation entry point.

`AnalysisResult` distinguishes a complete result from a capacity refusal. A guide-capacity refusal can retain exact token and active-pair coverage; callers must inspect the returned coverage before using a facet. Source identity and result acceptance belong to the host runtime and are absent from these values.

Token windows belong to an immutable result and remain readable after the input adapter is released. Range values reject negative and reversed offsets. Empty ranges are valid.

The module compiles and tests without the IntelliJ SDK. Run `:analysis-model:test` from the repository build, or use the independent `tools/pure-build` build to verify both platform-free modules.
