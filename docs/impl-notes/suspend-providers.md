# Coroutines Support Implementation Notes

These notes describe the current compiler and runtime design. Metro's coroutine support currently centers on suspend providers. User-facing documentation lives in [`docs/coroutines.md`](../coroutines.md).

## Model

Metro's suspend-provider bindings are gated by the `enable-suspend-providers` compiler option. The Gradle plugin exposes it as `metro.enableSuspendProviders`. It is disabled by default. Within Metro's binding model, the gate applies to every suspend binding, every suspend graph accessor, and every request using a suspend provider wrapper, including unscoped forms that only need the core `runtime` artifact. Separately, source code that names the public coroutine APIs must opt in to `ExperimentalMetroCoroutinesApi`.

The feature gate and runtime dependency are separate. When automatic runtime dependencies are enabled, the Gradle plugin adds `runtime-coroutines` whenever this option is enabled; it does not inspect source signatures to decide whether to add it.

The core `runtime` artifact defines `SuspendProvider` and `SuspendLazy`. It also contains helpers that need no coroutine coordination: `suspendProvider`, `suspendProviderOf`, `suspendLazyOf`, `map`, `flatMap`, and `zip`, plus adapters used by generated code. `suspendProviderOf` and `suspendLazyOf` wrap an existing value and do not run an initializer.

`runtime-coroutines` contains `suspendLazy`, `SuspendProvider.memoize()`, `memoizeAsLazy()`, and `SuspendDoubleCheck`. Metro uses `SuspendDoubleCheck` for scoped suspend bindings and generated memoizing `SuspendLazy` wrappers. This artifact has an implementation dependency on kotlinx-coroutines on every platform, but no kotlinx-coroutines type appears in Metro's public API.

Whether a binding requires a suspend context is determined separately for each graph. A binding requires one when its provider is a `suspend fun`, or when one of its eager dependencies requires one. Provider and lazy requests stop propagation because the consumer receives a handle instead of the value. Graph validation separately rejects a wrapper stack when the wrapper closest to the underlying value is synchronous. The same class can therefore require suspension in one graph but not another.

`SuspendBindingAnalysis` starts with directly suspend bindings and follows reverse dependency edges to mark their eager consumers. It caches resolved bindings and their edges. A child graph may query a parent before that parent has finished collecting bindings, so missing lookups are retried after the graph changes. Final validation stores the completed result for code generation.

Metro parses enabled provider and lazy wrappers recursively at any depth in a scalar wrapper stack. When `enableFunctionProviders` is also enabled, this includes `() -> T` and `suspend () -> T`.

Any scalar wrapper defers the dependency for suspend propagation. The wrapper nearest the underlying value then selects whether generated code starts from `Provider<T>` or `SuspendProvider<T>` and determines whether the request is valid:

- `suspend () -> T`, `SuspendProvider<T>`, and `SuspendLazy<T>` select `SuspendProvider<T>`.
- `Provider<T>`, `() -> T`, and `Lazy<T>` select `Provider<T>`. Graph validation rejects this form when `T` requires a suspend context, regardless of any outer wrappers. The same stack is valid when `T` does not require suspension.

This selected `Provider<T>` or `SuspendProvider<T>` is the canonical provider that code generation uses as the base for the full wrapper stack.

Outer wrappers only control how the immediate inner value is produced or cached. For example, `SuspendLazy<suspend () -> T>` caches the suspend function rather than its result.

Suspend wrappers in map value position do not support the same recursive nesting as scalar requests. `Map<K, suspend () -> V>` and `Map<K, SuspendProvider<V>>` are supported, including nullable `V`. The map is built synchronously and each value is initialized when its provider is invoked. Generated map multibinding factories retain non-null keys. `SuspendLazy` and additional wrapper layers in map value position are unsupported. Scalar wrappers may still surround the whole map.

A binding accessed through `ParentContext` keeps the owning graph's suspend classification. Unscoped factories copied into a child graph are instead analyzed in that child.

## Validation

Suspend-specific graph validation runs after Metro has built the ordinary binding graph:

1. Compute the complete set of bindings that require suspension.
2. Reject unsupported multibindings. `Set<T>` cannot contain suspend contributions, and a `Map<K, V>` with suspend values must be requested as `Map<K, suspend () -> V>` or `Map<K, SuspendProvider<V>>`.
3. Check every graph accessor. An unwrapped accessor for a suspend binding must itself be `suspend`. A wrapped accessor must have a suspend-capable wrapper nearest the underlying value, even when the accessor is `suspend`.
4. Reject member dependencies that require suspension. An ordinary constructor-injected class with injected members is also rejected when its constructor requires suspension because its graph-local suspend factory does not run member injection. Suspend assisted implementations are different: they support suspend constructor dependencies and run synchronous member injection after construction.
5. Reject dependency edges whose nearest wrapper is `Provider<T>`, `() -> T`, or `Lazy<T>` over a suspend binding.
6. Validate the assisted target's dependency wrappers, then require its factory SAM function to be `suspend` when constructing the target requires a suspend context.

FIR reports source-local errors that do not require graph resolution, including the feature gate, suspend `@Binds` and `@Multibinds`, and unsupported wrappers in map value position. These errors are available in the IDE. IR repeats the feature-gate check for suspend metadata read from compiled dependencies and handles checks that require the resolved graph. Named suspend failures use their dedicated `MetroDiagnosticId` and diagnostic factory. `METRO_ERROR` is reserved for `MetroDiagnosticId.GENERIC`.

Suspend diagnostics prefer the source declaration that requests the offending binding. The member-injection diagnostic specifically falls back to the graph declaration when the injected member has no reportable source. Dependency-cycle advice mentions suspend providers only when the feature is enabled and the cycle contains a binding that requires suspension.

One source-local check is still missing: injected members do not yet run the common FIR injection-site validation. IR still enforces the feature gate and rejects member dependencies that resolve to suspend bindings, but an unsupported suspend map wrapper over a synchronous member can currently pass validation.

## Code generation

### Factories

A suspend `@Provides` function gets a source-level `SuspendFactory<T>` with a suspend `invoke()`.

Ordinary providers and injected constructors can require suspension only in a particular graph, so their source-level factories remain synchronous. When generated graph code needs a `SuspendProvider<T>` for one of these bindings, `GraphSuspendFactoryGenerator` creates a private, IR-only `SuspendFactory<T>` inside that graph. Direct suspend access can call the constructor or provider function without creating this factory. The same graph-local factory approach is used for assisted-factory implementations.

Factory dependency fields depend on where the factory is generated:

- Ordinary source factories store unwrapped dependencies as `Provider<T>`.
- A source factory for a suspend `@Provides` function stores unwrapped dependencies as `SuspendProvider<T>` because source generation does not know how those dependencies resolve in a particular graph. Generated graph code adapts an ordinary provider with `SyncSuspendProvider` when necessary.
- An explicit wrapper stack uses `Provider<T>` or `SuspendProvider<T>` according to the wrapper nearest the underlying value.
- An IR-only graph suspend factory uses the resolved graph to choose `Provider<T>` or `SuspendProvider<T>` for each dependency.

Graph and binding-container receivers remain plain values.

Source factories deduplicate non-assisted parameters without defaults by their normalized binding key and whether the field is a `Provider<T>` or `SuspendProvider<T>`. Parameters with defaults remain separate because their defaults may differ. IR-only graph suspend factories keep one field per source parameter and choose `Provider<T>` or `SuspendProvider<T>` from the resolved graph.

### Graph fields

When a suspend binding needs a graph field, it is stored as `SuspendProvider<T>`. A generated non-suspend getter could not invoke it, and it cannot participate in fastInit's `SwitchingProvider`.

Scoped suspend fields are wrapped in `SuspendDoubleCheck`. Dependency cycles that pass through a suspend provider use `SuspendDelegateFactory`.

`BindingPropertyCollector` remains the source of truth for graph fields. It canonicalizes outer scalar wrappers while preserving map value structure, and `ParentContext` carries parent-owned fields into child graphs. Suspend bindings do not use a separate graph-field collection path.

### Access types and conversions

Generated expressions use three access types: instance, provider, and suspend provider. `BindingExpressionGenerator.toTargetType` performs these conversions:

- `Provider<T>` to `SuspendProvider<T>`: wrap with `SyncSuspendProvider`.
- `T` to `SuspendProvider<T>`: wrap with a suspend provider lambda.
- `SuspendProvider<T>` to `T`: invoke it from a suspend context.

Provider and lazy wrappers, generated adapters, and suspend map values preserve nullable types. When a scope or lazy wrapper caches a result, `null` counts as an initialized value.

Consumer boundaries recursively rebuild the requested wrapper stack from a canonical `Provider<T>` or `SuspendProvider<T>`. Ordinary lazy layers use `DoubleCheck.lazy`; suspend lazy layers use `SuspendDoubleCheck.lazy`. Provider layers return the recursively built inner value. This preserves each wrapper's own initialization and caching boundary. The existing `ProviderOfLazy` path remains an optimization for the exact `Provider<Lazy<T>>` form.

When a stack needs nested lambdas, Metro captures the canonical provider once before building them. The lambdas still invoke that provider only when their wrapper is used, and they do not recreate the provider object on every call.

An included graph accessor is also a source of wrappers. A non-suspend accessor can pass its value through unchanged when the consuming graph requests exactly the same wrapper stack. Otherwise Metro unwraps the accessor to a canonical provider and rebuilds the requested shape. Crossing a suspend wrapper produces a `SuspendProvider<T>`.

`SuspendDoubleCheck.lazy` returns its delegate unchanged when that delegate already implements `SuspendLazy`. This avoids adding a second cache around a scoped graph cache or another existing suspend-lazy implementation. `SuspendProvider.memoize()` delegates to `SuspendDoubleCheck.provider`, and `memoizeAsLazy()` delegates to `SuspendDoubleCheck.lazy`; an existing `SuspendLazy` therefore keeps its current concurrency behavior.

A deferred request for an entire collection, such as `suspend () -> Set<T>`, generates the ordinary provider form and adapts it with `SyncSuspendProvider`. This only works for synchronous set contributions; it does not enable suspend set contributions.

Graph validation records whether generated graph code needs `runtime-coroutines`: either for a scoped suspend binding or to materialize a memoizing `SuspendLazy` anywhere in a requested wrapper stack. If the artifact is missing, Metro reports a located `MISSING_RUNTIME_COROUTINES` diagnostic on the graph before code generation begins. Source factory generation reports the same diagnostic on injected declarations and provider parameters that require `SuspendLazy`, including modules that contain no graph. The diagnostic can appear alongside other graph validation errors.

### JS function types

The JVM, Native, and Wasm `SuspendProvider<T>` implementations also implement `suspend () -> T`. The JS implementation does not. Invoking a function-typed value compiles to a direct JS call, but a fun-interface instance is not a callable JS function.

Provider-framework conversion runs at each wrapper layer. On JS it wraps every function-provider and suspend-function-provider layer in a real lambda; other platforms can use the provider object directly. The conversion is not performed earlier because the same expression may also initialize a graph field whose required type is `Provider<T>` or `SuspendProvider<T>`.

`FunctionTypeInvocationOnAllPlatforms.kt` covers this path through the test framework's multiplatform `runBlocking` helper.

## Runtime behavior

`SuspendDoubleCheck` first reads a volatile cached-value field, so initialized calls do not acquire a lock. On the first call, it holds a private kotlinx-coroutines `Mutex` while invoking the delegate. Other callers suspend until that attempt finishes. The same implementation is used on JVM, Native, JS, and Wasm.

A successful result, including `null`, is cached and the delegate reference is released. If the delegate fails or is cancelled, the cache remains empty so a waiting or later caller can retry. Cancelling a caller that is waiting for the mutex does not cancel the caller already invoking the delegate.

Each initialization attempt adds a linked marker to its coroutine context. Structured child coroutines inherit it. A request for a cache already in that active chain fails before trying to acquire the same mutex. The marker is deactivated when the attempt finishes, so a child that outlives a failed or cancelled attempt can request the cache later without being mistaken for a cycle. Callers outside the active chain wait normally.

These markers do not coordinate separate initialization chains. Two independently launched coroutines can deadlock if each initializes one cache and then requests the other. Static graph validation catches cycles visible in the dependency graph. Dynamic provider and lazy calls must avoid acquiring caches in opposite orders.

`suspendLazy(SYNCHRONIZED)` uses `SuspendDoubleCheck`. `PUBLICATION` may run several initializers at once and publishes one result. JVM and Native use compare-and-set. JS and Wasm run on one thread, but initializers can overlap at suspension points, so the implementation checks again after an initializer returns before publishing. `NONE` caches the result when calls do not overlap but does not coordinate concurrent callers. A failed or cancelled attempt does not publish a value in any mode.

## Tracing

Suspend traces follow the coroutine across suspension and thread changes. `TracedSuspendProvider` wraps provider values, while direct suspend expressions use `MetroTraceContext.traceSuspend`. Both paths delegate to `Tracer.traceCoroutine`.

## Limitations and future work

Metro currently initializes constructor and provider arguments sequentially in the caller's coroutine context. Graphs cache scoped values, but they do not own a `CoroutineScope` or `Job` and have no resource-cleanup lifecycle.

The possible additions below are independent.

### Member injection

Ordinary constructor-injected classes cannot combine suspend construction with member injection. Supporting this would require their graph-local suspend factory to construct the instance and then run the existing synchronous member injectors.

Suspend assisted-factory implementations already do this for synchronous injected members. Suspend member dependencies and suspend graph injector functions remain separate future work.

Supporting actual `@Inject suspend fun` members is a separate feature. It would need rules for inherited members, cross-module metadata, a possible `SuspendMembersInjector` API, and Dagger interop, whose `MembersInjector` has no suspend form.

### Warm-up

Applications can warm selected graph roots today by calling their accessors concurrently from an application-owned `coroutineScope`. This keeps the caller's dispatcher, cancellation, and trace context.

If Metro adds a warm-up API, roots should be selected explicitly. Warming every scoped binding could run unused side effects and retain values the application never requests. Calling selected roots concurrently already lets `SuspendDoubleCheck` initialize shared dependencies once.

Ordinary graph access remains sequential. If Metro adds parallel initialization, it should happen at an explicit warm-up boundary rather than at every construction site.

### Graph lifecycle and resource cleanup

Warm-up does not require a graph-owned scope. A graph lifecycle would be a separate change to the execution model.

Initialization currently runs in the caller. Adding a `Job` field would not make that work a child of the graph job; Metro would have to launch it in a graph-owned scope. A design would need to define caller cancellation, dispatchers, tracing, parent and child graph ownership, and behavior after the graph is closed.

Cancelling in-flight work is not resource cleanup. Closing an initialized database or other cached value needs a separate ownership and disposal contract.

### Unsupported APIs and shapes

`Deferred<T>` is not an injectable wrapper. It exposes job lifecycle operations such as `cancel()` to every consumer and requires an owning `CoroutineScope`. Applications that need a `Deferred` can provide one from a scope they own.

Provider-valued sets, including `Set<suspend () -> T>`, are unsupported in the same way as `Set<Provider<T>>`. `SuspendLazy` and additional suspend-wrapper layers in map value position, including `Map<K, SuspendLazy<V>>`, are also unsupported.

### Multiplatform test coverage

Multiplatform suspend box fixtures use the compiler test framework's `runBlocking` helper. It starts a coroutine and expects it to finish before the helper returns, so it only supports calls that complete synchronously. This avoids the awkward event-loop behavior of trying to block a JS box test. `ENABLE_SUSPEND_PROVIDERS` adds `runtime-coroutines` and the kotlinx-coroutines classpath to a fixture unless it also uses `WITHOUT_RUNTIME_COROUTINES`. Tests for real suspension, scheduling, cancellation, and contention live in the runtime modules and use kotlinx-coroutines directly.
