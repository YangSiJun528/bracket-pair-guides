# Integration decisions

- Keep five production modules and current IDE support/ADR behavior. No internal interface compatibility adapters.
- core owns complete analyze/repair attempt and unrelated-write capture retry. runtime owns SDK input, request lifetime, source validity and final EDT acceptance.
- BracketView query contract is model-owned. Host stamp is runtime-private.
- GuideWork/GuideView/GuideDemand contracts are UI-owned. Single reconcile demand hides cancellation/launch ordering; refresh is an independent thread-safe wake-up.
- Full/repair/native tickets are separate; caret changes do not invalidate valid full computation. UI owns only guide/display revision and markup.
- runtime factory exports UI contract types only; core is implementation dependency. UI/core/runtime exclusion uses actual compiler inputs, not internal visibility alone.
- Input facts remain bounded and immutable; no whole-document eager copy or token-object inflation. Canonical sharing and memory bounds must survive with tests and measurements.
- All existing test implementations/assets are replaced. Existing test/measurement engines are reused. Historical raw evidence is preserved.
- New ArchUnit rules supplement TestKit: outside core cannot use core internals; runtime uses UI work contracts, not UI internals; pure UI policy excludes SDK; UI owns no Job.
- SDK-free build uses a separate tools/pure-build settings entrypoint and independent output/cache.
- Packaging byte checks use actual packaging inputs, including instrumentation when applied.
- Source/tests may be temporarily red during integration; empty suites and skipped validations never imply completion.
