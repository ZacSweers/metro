# Suspend Provider Notes

Internal notes for Metro's suspend/coroutines support. User-facing docs live in
[`docs/coroutines.md`](../coroutines.md).

## Model

Suspend providers are gated by the `enable-suspend-providers` compiler option, exposed as
`metro.enableSuspendProviders` in the Gradle plugin. It is false by default. The Gradle plugin adds
`runtime-coroutines` to the compilation when the option is enabled and automatic runtime
dependencies are on.

Suspend-ness is a per-graph propagated property, not a per-declaration annotation. A binding
is suspend in a given graph if its provider is a `suspend fun`, or if it depends (unwrapped) on a
suspend binding. The same class can be suspend in one graph and not in another depending on what
its dependencies resolve to.

`SuspendBindingAnalysis` computes the propagation fixpoint only when suspend providers are
enabled. Final validation analyzes the complete graph, while child graph validation incrementally
analyzes the unsealed parent's bindings. Both paths use the same rules and produce a `suspendSet`
of type keys. Codegen consumes the final set via `isTransitivelySuspend(typeKey)`. Three
consumption shapes stop propagation because they defer the suspension to the consumer's own
suspend context:

- `suspend () -> T` / `SuspendProvider<T>` (raw type; users write the function type)
- `SuspendLazy<T>` (defers + memoizes per wrapper instance)
- `Map<K, suspend () -> V>` (the map resolves synchronously; each value defers itself)

`GraphDependency` bindings derive `isSuspend` from suspend accessors, deferred-suspend accessor
return types, or parent-context tokens. Parent tokens query the owning graph's analysis before it
is sealed so graph extensions validate against the same suspend propagation that the parent will
use during codegen.

## Validation (`validateSuspendBindings`)

Ordered steps, all graph-level errors:

1. Seed + fixpoint propagation (above).
2. Multibinding aggregates: `Set<T>` cannot contain suspend contributions at all; scalar
   `Map<K, V>` over suspend values errors (only the deferred value form is legal).
3. Accessors: iterate `node.accessors` directly rather than the deduped roots map, which collapses
   accessors sharing a contextual key). Non-suspend accessor over a suspend binding errors unless
   the contextual key defers. `Provider<T>`/`Lazy<T>` roots over suspend bindings error here too.
   The dependency-edge check in step 4 never sees roots.
   3b. Member injection: a `MembersInjected` binding or a constructor-injected class with
   `@Inject` members errors if transitively suspend at all (member injection has no suspend form,
   and nested suspend factories do not run member injection).
4. Wrapping conflicts on dependency edges: `Provider<T>`/`Lazy<T>` wrapping a suspend binding.
5. Assisted factories: the SAM must be `suspend` if the target consumes suspend bindings
   unwrapped. Provider/Lazy-wrapped suspend deps of the target are checked here as well because
   the target binding is not in the graph and step 4 never visits it.

Unsupported wrapper *nesting* (suspend wrapper around any other wrapper, Provider/Lazy around a
suspend wrapper, `Map<K, SuspendLazy<V>>`) is rejected earlier, at FIR
(`UNSUPPORTED_SUSPEND_WRAPPER_NESTING` in `injectionSiteChecks.kt`).

## Codegen

### Factories

A suspend `@Provides` gets a `SuspendFactory<T>` (suspend `invoke()`) instead of `Factory<T>`. For a
non-suspend provider or constructor that is transitively suspend in a graph, its source-compiled
factory is a plain `Factory<T>` whose `create()` expects `Provider` args, but the graph holds
`SuspendProvider`s for its deps. `GraphSuspendFactoryGenerator` emits
private IR-only nested `SuspendFactory<T>` classes per graph for these bindings (and an
assisted-impl variant). Field types per dep: `SuspendProvider<T>` for suspend deps (including
canonicalized `SuspendLazy` params), `Provider<T>` otherwise, plain values for receivers.

Per-class factory parameter dedup (`dedupeParameters`) keys on `(typeKey, suspend-shape)`:
suspend-shaped params (SuspendProvider/SuspendLazy) are backed by `SuspendProvider` fields while
other shapes share a canonical `Provider` field, and the two cannot reconstruct each other's
access in a non-suspend factory.

### Properties

`BindingPropertyCollector` forces suspend bindings to `FIELD` properties (a non-suspend `GETTER`
over a suspend binding would be invalid IR) typed `SuspendProvider<T>`, and excludes suspend
bindings from fastInit's SwitchingProvider. Scoped suspend bindings wrap in `SuspendDoubleCheck`.
Cycles use `SuspendDelegateFactory`.

### Access types and conversions

`AccessType` is INSTANCE / PROVIDER / SUSPEND_PROVIDER. Conversions in
`BindingExpressionGenerator.toTargetType`:

- PROVIDER → SUSPEND_PROVIDER: `SyncSuspendProvider` (`@JvmInline` view, allocation-free).
- INSTANCE → SUSPEND_PROVIDER: wrap in a suspend provider lambda.
- SUSPEND_PROVIDER → INSTANCE: suspend `invoke()` (only valid in suspend contexts, which
  validation guarantees).

`SuspendLazy<T>` requests are intercepted at the top of `generateBindingCode`: generate the
`SuspendProvider` form and wrap in `SuspendDoubleCheck.lazy`, which short-circuits when the
delegate is already the graph's `SuspendDoubleCheck` (scoped bindings share the graph cache).
Whole-collection suspend access to multibindings (`suspend () -> Set<T>` etc.) generates the
Provider form and adapts via `SyncSuspendProvider`.

Graph sealing records whether reachable bindings need `runtime-coroutines`: either a scoped
suspend binding or a `SuspendLazy` request. Codegen checks that bit before constructing the graph
generator and reports the missing artifact as a normal Metro error.

### JS function types

`SuspendProvider<T>` mirrors `Provider<T>`'s expect/actual shape. Its JVM, Native, and Wasm actuals
extend `suspend () -> T`. Its JS actual does not because calling a function-typed value compiles to
a direct JS call, while a fun-interface instance is not a callable JS function.

`typeAsProviderArgument` performs function-type adaptation at the consumer boundary. For a
suspend-provider-shaped contextual key, it passes the expression through
`ProviderFramework.convertTo`. On JS, `toSuspendFunctionType` wraps `SuspendProvider` values in a
suspend lambda. Other platforms need no conversion.

The `generateBindingCode` and `toTargetType` layers do not perform this conversion because their
expressions also initialize `SuspendProvider<T>` graph fields. Converting there would produce
field initializers with the wrong type.

`toSuspendFunctionType` must `patchDeclarationParents` on the wrapping lambda: the wrapped
expression can itself contain lambdas parented to the enclosing declaration, and they get
re-parented into the new lambda.

`FunctionTypeInvocationOnAllPlatforms.kt` covers the end-to-end JS invocation path using stdlib
`startCoroutine` (non-suspending providers complete synchronously, so no `runBlocking` needed).

## Runtime (`runtime-coroutines`)

`SuspendDoubleCheck` follows the `DoubleCheckInitGuard` pattern: the memoization algorithm lives
in common code; the `SuspendDoubleCheckInitGuard` expect/actual superclass provides
synchronization. JVM/Native (`nonWebMain`) use a coroutine `Mutex`. JS/Wasm (`webMain`) use a plain
flag plus a FIFO queue of stdlib continuations. Single-threaded platforms need no atomics or
parking, which keeps the web klibs free of kotlinx-coroutines. This also lets JS box tests link
`runtime-coroutines` without partial-linkage errors against dev test compilers. The kotlinx
dependency lives only in `nonWebMain`.

The delegate runs with a context marker containing the `SuspendDoubleCheck` and caller identity.
A recursive call sees its marker and fails before trying to reacquire the non-reentrant guard. An
independent coroutine with the same `Job` or coroutine context has no marker, so it waits for the
in-flight value instead of being reported as a cycle. Markers retain their parent so indirect
cycles are detected too.

Semantics on all platforms: single-flight, failures retried, cancellation mid-init leaves the
cache untouched, reentrant cycles fail fast. `SuspendLazy` is not serializable
because `writeReplace` cannot force a suspend computation.

## Tracing

Suspend spans use `Tracer.traceCoroutine` (`MetroTraceContext.traceSuspend`), which handles
propagation-token installation through structured concurrency. `TracedSuspendProvider` decorates
suspend providers; `BindingExpressionDecorator.decorateSuspendProviderExpression` and the
`isSuspend` flag on direct-expression requests are the hook points.

## Current limitations

- Suspend member injection (`@Inject suspend fun`) is rejected at FIR.
- `Deferred<T>` as an injectable wrapper is rejected. A Deferred is a Job and leaks lifecycle
  controls to consumers (see the FAQ).
- Graph-owned `CoroutineScope` / `warmUp()` is future work.
