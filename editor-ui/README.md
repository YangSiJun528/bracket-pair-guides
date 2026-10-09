# Editor presentation ownership

`ui.work` is the public boundary between presentation and execution. `GuideDemand`
is a complete desired state: presentation never sequences worker cancellation,
repair, or full analysis. `refresh()` is an independent daemon wake-up.

`ui.editor.EditorGuide` owns presentation revisions, tracked pair markers, visible
token windows, geometry, and markup. It does not own accepted calculation results,
source stamps, coroutine scopes, or jobs. A document edit updates or hides affected
guide pixels synchronously before submitting one content demand. A result is
applied on EDT. Every synchronous rendering entry acquires a `RenderFrames.Frame`,
including reentrant results with the same demand revision. SDK markup resources
carry their owning frame; a fresh frame adopts reused resources. An obsolete
frame rolls back only its own newly created resources, so it cannot erase a fresh
reentrant result. A resource is marked retiring before SDK removal, preventing a
`beforeRemoved` callback from adopting a resource already being disposed. Close
revokes all frames. The SDK forbids creating range markers during a removal
listener; fresh rendering is tested during supported addition callbacks, and
removal callbacks are tested for close and cleanup. Rendering failures propagate to the
runtime without claiming acceptance; cleanup does not conceal the original error.

`ui.policy` resolves support and activity into analysis coverage and presentation.
`ui.editor.events` adapts SDK events and owns native setting transactions.
`ui.editor.highlighting.NativeGuideAdvisory` owns notification episodes and
suppression; native proof collection belongs to analysis-runtime.

The module compiles against analysis-model and IntelliJ. It cannot compile against
analysis-core or analysis-runtime. Tests ending in `IdeContractTest` exercise the
real SDK boundary through `GuideWorkFactory`; ordinary policy tests need no IDE
fixture.
