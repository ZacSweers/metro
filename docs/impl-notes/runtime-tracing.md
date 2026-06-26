# Runtime Tracing Notes

Internal notes for Metro's generated runtime tracing implementation. User-facing setup docs live in
[`docs/performance.md`](../performance.md#runtime-tracing).

## Goal

`metro { enableRuntimeTracing.set(true) }` makes generated graph code emit AndroidX Tracing events
while user code runs. This is separate from Metro's compiler self-tracing, which records FIR and IR
extension work during compilation.

Runtime tracing records two kinds of work:

- graph entry-point instants, such as accessors, graph extension creators, and member-injection
  functions
- binding-realization spans, such as constructor injection, `@Provides`, multibindings, and
  provider invocation

The implementation currently targets JVM and Android JVM code (what androix.trace targets). The helper runtime lives in 
the JVM-only `metro-trace` artifact.

## Graph Input Contract

Runtime tracing needs an AndroidX `Tracer` instance before generated graph code can create any
binding spans or entry-point instant events. Metro does not synthesize this binding. Root dependency
graphs must use a graph factory whose create function takes a
`@Provides tracer: androidx.tracing.Tracer` graph input.

FIR enforces that contract in two places:

- `DependencyGraphChecker`: root `@DependencyGraph` declarations must declare a
  `@DependencyGraph.Factory`.
- `DependencyGraphCreatorChecker`: root graph factory create functions must include the
  `@Provides Tracer` input.

Both diagnostics report `METRO_TRACE_ERROR`. Graph extensions do not take their own tracer input.
They inherit the parent graph's trace context.

## Runtime Helpers

`metro-trace` contains the small (internal) runtime surface generated code calls:

- `MetroTraceContext`: immutable graph-local trace state.
- `TracedProvider`: a Metro `Provider<T>` decorator that traces each provider invocation.
- `TraceTestUtil`: test-only helpers used by compiler box tests.

`MetroTraceContext` stores the AndroidX `Tracer`, a category, the current graph name, and the
root-to-current graph path. It does not store propagation tokens or mutable current-span state.
Generated graph implementations and provider fields can be reused across threads, so Metro keeps
the context immutable and leaves thread-local behavior to AndroidX Tracing.

Graph extensions call `parent.metroTraceContext.child(graphName)`. The child context reuses the
same underlying `Tracer` and only extends the graph path metadata.

## Availability

The Gradle plugin adds `metro-trace` automatically for JVM and Android compilations when
automatic runtime dependencies are enabled and `enableRuntimeTracing` is true.

IR still validates availability because users can disable automatic dependencies or invoke the
compiler directly. `RuntimeTracingAvailability` memoizes one compilation-wide decision:

- the option must be enabled
- the platform must support tracing
- `androidx.tracing.Tracer` must be resolvable
- `MetroTraceContext`, `MetroTraceContext.trace`, `MetroTraceContext.instant`,
  `MetroTraceContext.child`, and `TracedProvider` must be resolvable

`DependencyGraphTransformer` reports the unavailable reason once as `METRO_TRACE_ERROR`, then exits
processing. FIR intentionally does not do classpath symbol checks for these helper classes.

## Generated Graph State

`IrGraphGenerator` creates a private `metroTraceContext` property on generated graph
implementations when runtime tracing is available.

For root graphs, the initializer resolves the user-provided tracer graph input and creates:

```kotlin
MetroTraceContext(
  tracer = tracer,
  category = "dev.zacsweers.metro",
  graphName = "...",
  graphPath = "...",
)
```

For graph extensions, the initializer reads the parent graph's generated context and calls
`child(graphName)`.

The tracer binding and trace context binding are infrastructure. `RuntimeTraceRendering` marks
`androidx.tracing.Tracer` and `MetroTraceContext` as runtime-tracing infra so generated code does
not trace the act of reading the tracer or trace context itself.

## Binding Spans

`BindingExpressionDecorator` is the codegen hook for expression-level tracing. `IrDependencyGraph`
injects either `RuntimeTracingBindingExpressionDecorator` or `BindingExpressionDecorator.None`
based on `RuntimeTracingAvailability`.

The decorator runs at Metro's shared binding conversion points instead of being scattered through
each binding branch:

- `BindingExpressionGenerator.maybeTraceDirectExpression`: wraps direct value expressions in
  `MetroTraceContext.trace { ... }`.
- `BindingExpressionGenerator.toTargetType`: decorates provider-valued expressions before final
  provider interop conversion.

Direct value spans cover expressions such as constructor calls, `@Provides` calls, and aggregate
multibinding getters.

Provider spans use `TracedProvider`. Before wrapping, the decorator normalizes the provider
expression to Metro's `Provider<T>`. The regular provider conversion path can then convert the
traced Metro provider back to Dagger, Javax, Jakarta, Guice, or function-provider types. Reads of
stored provider properties are not wrapped again because those fields are initialized with the
traced provider.

Multibinding code uses the same hooks. The aggregate getter is traced as `Multibinding`, and each
element binding can still emit its own binding span when it is realized.

## Entry-Point Instants

`IrGraphGenerator.traceGeneratedGraphEntryPoint(...)` emits generated graph APIs as instant events.
These markers make it clear which graph operation caused lower-level binding spans without making
the graph entry point look like its own binding.

Current entry-point kinds are:

- `Accessor`: graph accessors and graph extension creators.
- `Member Injector`: generated member-injection functions.

These entry-point markers use `MetroTraceContext.instant(...)`. Their visible names render the
implemented graph member, such as `AppGraph.foo` or `AppGraph.createChildGraph`. Metadata records
the callable name separately without using binding-kind metadata.

## Names And Metadata

Binding span names are intentionally readable rather than globally unique. The visible name is:

```text
<qualifier> <contextual type>
```

The qualifier is omitted for unqualified bindings. The contextual type falls back to the canonical
type when they are the same.

Every Metro runtime trace event records:

- `metro.graph`: the graph that owns the generated code.
- `metro.graph_path`: the root-to-current graph path.

Binding span metadata:

- `metro.type`: the canonical unqualified type.
- `metro.binding_kind`: the generated binding kind.

Optional metadata:

- `metro.contextual_type`: the requested unqualified type when it differs from `metro.type`.
- `metro.qualifier`: the rendered qualifier.

Entry-point instant metadata:

- `metro.callable`: the callable name without the graph prefix.
- `metro.type`: the canonical unqualified requested type.
- `metro.contextual_type`: the requested unqualified type when it differs from `metro.type`.
- `metro.qualifier`: the rendered qualifier.
- `metro.entry_point_kind`: `Accessor` or `Member Injector`.

Multibinding element rendering is special-cased. The element's visible type remains the element
type, but qualifier rendering uses the target multibinding when that is the useful user-facing key.

## Integration Points

The Android sample under `samples/android-app` shows the app-side setup:

- the graph factory takes the `Tracer` input
- `MetroApp` owns the `TraceDriver` and `TraceSink`
- AndroidX tracing 2.0.0-alpha09 defers `TraceSink` writer creation, so Android apps can create the
  graph from `Application.onCreate` without keeping the graph lazy just to avoid early file setup
- the AndroidX profiler initializer is removed so profiler broadcasts use the app's driver
- the UI can flush pending trace data

Benchmark generation has a separate `--enable-runtime-tracing` mode. It is Metro-only, enables the
compiler option on generated projects, passes a tracer through generated graph factories, and copies
Perfetto trace files into startup benchmark result directories.

## Tests

Runtime tracing coverage is mostly functional:

- `compiler-tests/src/test/data/box/tracing`: box tests that exercise generated graphs and assert
  recorded trace events with `testMetroTrace`.
- `compiler-tests/src/test/data/diagnostic/dependencygraph/RuntimeTracingRequires*`: FIR
  diagnostics for missing graph factories and missing tracer factory inputs.
- `compiler-tests/src/test/data/dump/ir/dependencygraph/RuntimeTracing.kt`: IR dump coverage for
  generated trace context and wrapper placement.
- `metro-trace/src/test`: runtime helper tests for `MetroTraceContext` and `TracedProvider`.
- `gradle-plugin/src/functionalTest/.../RuntimeTracingConfigurationTest.kt`: verifies automatic
  `metro-trace` dependency wiring.

Prefer adding box tests when changing behavior. IR dumps are useful for wrapper placement, but the
box tests confirm that emitted trace events are observable in execution order.
