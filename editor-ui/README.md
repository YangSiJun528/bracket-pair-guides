# Editor presentation ownership

`ui.work` is the public boundary between presentation and execution. `GuideDemand`
is a complete desired state: presentation never sequences worker cancellation,
repair, or full analysis. `refresh()` is an independent daemon wake-up.

`ui.editor.EditorGuide` owns presentation revisions, tracked pair markers, visible
token windows, geometry, and markup. It does not own accepted calculation results,
source stamps, coroutine scopes, or jobs. A document edit updates or hides affected
guide pixels synchronously before submitting one content demand. A result is
applied on EDT; reentrant changes revoke the presentation revision and rendering
failures propagate to the runtime without claiming acceptance.

`ui.policy` resolves support and activity into analysis coverage and presentation.
`ui.editor.events` adapts SDK events and owns native setting transactions.
`ui.editor.highlighting.NativeGuideAdvisory` owns notification episodes and
suppression; native proof collection belongs to analysis-runtime.

The module compiles against analysis-model and IntelliJ. It cannot compile against
analysis-core or analysis-runtime. Tests ending in `IdeContractTest` exercise the
real SDK boundary through `GuideWorkFactory`; ordinary policy tests need no IDE
fixture.
