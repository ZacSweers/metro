# Coroutines Support Implementation Notes

Internal notes for Metro's coroutine support, which currently centers on suspend providers.
User-facing documentation lives in [`docs/coroutines.md`](../coroutines.md).

## Model

Suspend providers are gated by the `enable-suspend-providers` compiler option, exposed as
`metro.enableSuspendProviders` in the Gradle plugin. It is disabled by default. The gate applies to
every suspend binding, every suspend graph accessor, and every request using a suspend provider
wrapper, including unscoped forms whose generated code only needs the core `runtime` artifact.

The feature gate and runtime dependency are separate. When automatic runtime dependencies are
enabled, the Gradle plugin adds `runtime-coroutines` whenever this option is enabled; it does not
inspect source signatures to decide whether to add it.

The core `runtime` artifact contains `SuspendProvider`, `SuspendLazy`, `suspendProvider`,
`suspendProviderOf`, `suspendLazyOf`, the provider operators, and the adapters used by generated
code. `runtime-coroutines` contains the synchronization used by scoped suspend bindings,
Metro-generated memoizing `SuspendLazy` wrappers, and `suspendLazy`.

Whether a binding requires suspension is determined separately for each graph. A binding requires
suspension when its provider is a `suspend fun`, or when it directly consumes another suspend
binding. The same class can therefore require suspension in one graph but not another.

`SuspendBindingAnalysis` discovers each binding's dependency edges once and records the reverse
edges from a dependency to its consumers. A worklist then propagates suspension from directly
suspend bindings through those consumers. The analysis is incremental because a child graph may
query its parent before the parent is sealed; unresolved parent keys are retried as the graph grows.
Final validation analyzes the complete graph, and validation and code generation share the result.

`Provider`, function providers, `Lazy`, `SuspendProvider`, suspend functions, and `SuspendLazy`
compose recursively at any depth in a scalar wrapper stack. The innermost wrapper determines the
stored provider type and whether a suspend binding can be initialized:

- An innermost `suspend () -> T`, `SuspendProvider<T>`, or `SuspendLazy<T>` uses
  `SuspendProvider<T>` storage and stops suspend propagation.
- An innermost `Provider<T>`, `() -> T`, or `Lazy<T>` uses `Provider<T>` storage. Graph validation
  rejects it when `T` resolves to a suspending binding, regardless of any outer wrappers. The same
  wrapper stack is valid when `T` is not suspending.

Outer wrappers only control how the immediate inner value is produced or cached. For example,
`SuspendLazy<suspend () -> T>` caches the suspend function rather than its result.

`Map<K, suspend () -> V>` and `Map<K, SuspendProvider<V>>` remain the supported suspend map-value
forms. The map is built synchronously and each value is initialized when its provider is invoked.

Bindings from parent graphs keep the parent's suspend requirement. Child graphs query the graph that
owns the binding rather than recomputing a different result.

## Validation

Validation runs in this order:

1. Compute the complete set of bindings that require suspension.
2. Reject unsupported multibindings. `Set<T>` cannot contain suspend contributions, and a
   `Map<K, V>` with suspend values must be requested as `Map<K, suspend () -> V>` or
   `Map<K, SuspendProvider<V>>`.
3. Check every graph accessor. An accessor for a suspend binding must be `suspend` or return a
   supported deferring wrapper. `Provider<T>` and `Lazy<T>` accessors cannot expose suspend
   bindings.
4. Reject member-injection paths that require suspension. A class with `@Inject` members is also
   rejected when its construction requires suspension because graph-local suspend factories do not
   run ordinary member injection afterward.
5. Reject dependency edges where `Provider<T>`, `() -> T`, or `Lazy<T>` wraps a suspend binding.
6. Require an assisted factory's SAM function to be `suspend` when constructing its target requires
   suspension.

FIR handles checks that do not require graph resolution: the feature gate, suspend `@Binds` and
`@Multibinds`, and unsupported suspend wrappers in map value position. Graph validation checks
whether an otherwise valid `Provider<T>`, `() -> T`, or `Lazy<T>` resolves to a suspending `T` in
that graph.

## Code generation

### Factories

A suspend `@Provides` function gets a source-level `SuspendFactory<T>` with a suspend `invoke()`.

Ordinary providers and injected constructors can require suspension only in a particular graph, so
their source-level factories remain synchronous. `GraphSuspendFactoryGenerator` creates private,
IR-only `SuspendFactory<T>` classes inside the generated graph for those bindings. The same approach
is used for assisted-factory implementations.

Each generated factory stores:

- `SuspendProvider<T>` for dependencies that require suspension in that graph.
- `Provider<T>` for ordinary dependencies.
- Plain values for graph or binding-container receivers.

Parameters that resolve the same key share a field when their innermost wrappers both use
`Provider<T>` or both use `SuspendProvider<T>`. An innermost suspend provider or `SuspendLazy` uses
a canonical `SuspendProvider<T>` field; an innermost ordinary provider or `Lazy` uses
`Provider<T>`.

### Graph fields

Suspend bindings are always stored in `SuspendProvider<T>` fields. A generated non-suspend getter
could not invoke them, and they cannot participate in fastInit's `SwitchingProvider`.

Scoped suspend fields are wrapped in `SuspendDoubleCheck`. Dependency cycles that pass through a
suspend provider use `SuspendDelegateFactory`.

### Access types and conversions

Generated expressions use three access types: instance, provider, and suspend provider.
`BindingExpressionGenerator.toTargetType` performs these conversions:

- `Provider<T>` to `SuspendProvider<T>`: wrap with `SyncSuspendProvider`.
- `T` to `SuspendProvider<T>`: wrap with a suspend provider lambda.
- `SuspendProvider<T>` to `T`: invoke it from a suspend context.

These types and adapters support nullable `T`.

Consumer boundaries recursively rebuild the requested wrapper stack from a canonical `Provider<T>`
or `SuspendProvider<T>`. Ordinary lazy layers use `DoubleCheck.lazy`; suspend lazy layers use
`SuspendDoubleCheck.lazy`. Provider layers return the recursively built inner value. This preserves
each wrapper's own initialization and caching boundary. The existing `ProviderOfLazy` path remains an
optimization for the exact `Provider<Lazy<T>>` form.

An included graph accessor is also a source of wrappers. When the consuming graph requests the same
wrapper stack, generated code passes it through unchanged. For another request shape, generated code
unwraps the accessor to a canonical provider first; crossing any suspend wrapper produces a
`SuspendProvider<T>`.

If a suspend provider is already the scoped graph cache, `SuspendDoubleCheck.lazy` reuses that cache
instead of adding another memoization layer. A deferred request for an entire collection, such as
`suspend () -> Set<T>`, generates the ordinary provider form and adapts it with
`SyncSuspendProvider`.

Graph validation records whether generated graph code needs `runtime-coroutines`: either for a
scoped suspend binding or to materialize a memoizing `SuspendLazy` anywhere in a requested wrapper
stack. If the artifact is missing, Metro reports a located `MISSING_RUNTIME_COROUTINES` diagnostic
on the graph before code generation begins. Source factory generation reports the same diagnostic
on injected declarations and provider parameters that require `SuspendLazy`, including modules
that contain no graph. The diagnostic can appear alongside other graph validation errors.

### JS function types

The JVM, Native, and Wasm `SuspendProvider<T>` implementations also implement `suspend () -> T`.
The JS implementation does not. Invoking a function-typed value compiles to a direct JS call, but a
fun-interface instance is not a callable JS function.

Provider-framework conversion runs at each wrapper layer. On JS it wraps every function-provider
and suspend-function-provider layer in a real lambda; other platforms can use the provider object
directly. The conversion is not performed earlier because the same expression may also initialize a
graph field whose required type is `Provider<T>` or `SuspendProvider<T>`.

`FunctionTypeInvocationOnAllPlatforms.kt` covers this path through the test framework's
multiplatform `runBlocking` helper.

## Runtime behavior

`SuspendDoubleCheck` allows one initializer to run at a time. Other callers wait. The first
successful result is cached; failure or cancellation leaves the cache empty so a later caller can
retry. Recursive initialization fails with a cycle error instead of waiting on itself.

All platforms use a coroutine `Mutex` from kotlinx-coroutines.

Cycle detection uses a coroutine-context marker for each active `SuspendDoubleCheck`. The marker is
inherited by structured child coroutines and retains its parent, so direct and indirect requests for
the same cache in one initialization chain fail before trying to reacquire the mutex. Callers
outside that chain wait for the in-flight value normally.

The markers do not coordinate separate initialization chains. Two independently launched
coroutines can deadlock if each initializes one cache and then requests the other. Static graph
validation catches cycles visible in the dependency graph. Dynamic provider or lazy invocation
must avoid acquiring caches in opposite orders.

Injected `SuspendLazy` uses the same single-flight behavior. The standalone `suspendLazy` factory
follows its `LazyThreadSafetyMode`: `SYNCHRONIZED` is single-flight, `PUBLICATION` allows
initializers to overlap and caches one result, and `NONE` does not coordinate callers.

## Tracing

Suspend traces use `Tracer.traceCoroutine` through `MetroTraceContext.traceSuspend`, so a trace
follows its coroutine across suspension and thread changes. `TracedSuspendProvider` applies the
runtime wrapper. `BindingExpressionDecorator.decorateSuspendProviderExpression` and the
direct-expression `isSuspend` flag are the compiler hook points.

## Limitations and future work

Metro currently initializes constructor and provider arguments sequentially in the caller's coroutine
context. Graphs cache scoped values but do not own a `CoroutineScope`, a `Job`, or resource cleanup.

The possible additions below are independent.

### Member injection

The smallest extension is to let a graph-local suspend factory construct an instance and then run
its existing synchronous member injection. Suspend graph injector functions could similarly await
their arguments before calling the existing setter functions.

Supporting actual `@Inject suspend fun` members is a separate feature. It would need rules for
inherited members, cross-module metadata, a possible `SuspendMembersInjector` API, and Dagger
interop, whose `MembersInjector` has no suspend form.

### Warm-up

Applications can warm selected graph roots today by calling their accessors concurrently from an
application-owned `coroutineScope`. This keeps the caller's dispatcher, cancellation, and trace
context.

If Metro adds a warm-up API, roots should be selected explicitly. Warming every scoped binding could
run unused side effects and retain values the application never requests. Calling selected roots
concurrently already lets `SuspendDoubleCheck` initialize shared dependencies once.

Metro will continue initializing arguments sequentially during ordinary graph access. Parallel work
belongs at an explicit application warm-up boundary rather than each construction site.

### Graph lifecycle and resource cleanup

Warm-up does not require a graph-owned scope. A graph lifecycle would be a separate change to the
execution model.

Initialization currently runs in the caller. Adding a `Job` field would not make that work a child
of the graph job; Metro would have to launch it in a graph-owned scope. A design would need to define
caller cancellation, dispatchers, tracing, parent and child graph ownership, and behavior after the
graph is closed.

Cancelling in-flight work is not resource cleanup. Closing an initialized database or other cached
value needs a separate ownership and disposal contract.

### Unsupported APIs and shapes

`Deferred<T>` is not an injectable wrapper. It exposes job lifecycle operations such as `cancel()`
to every consumer and requires an owning `CoroutineScope`. Applications that need a `Deferred` can
provide one from a scope they own.

Provider-valued sets, including `Set<suspend () -> T>`, are unsupported in the same way as
`Set<Provider<T>>`. `SuspendLazy` and additional suspend-wrapper layers in map value position,
including `Map<K, SuspendLazy<V>>`, are also unsupported.

### Multiplatform test coverage

Multiplatform suspend box fixtures use the compiler test framework's `runBlocking` helper. It runs
providers that complete synchronously without adding kotlinx-coroutines to the test fixture.
