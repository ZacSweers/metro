// ENABLE_SUSPEND_PROVIDERS
// DESUGARED_PROVIDER_SEVERITY: NONE
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Eager requests still form hard cycles alongside deferred requests, regardless of argument order.
@Inject class LazyFirstA(val deferred: Lazy<LazyFirstB>, val eager: LazyFirstB)

@Inject class LazyFirstB(val a: LazyFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>LazyFirstGraph<!> {
  val value: LazyFirstA
}

@Inject class EagerLazyFirstA(val eager: EagerLazyFirstB, val deferred: Lazy<EagerLazyFirstB>)

@Inject class EagerLazyFirstB(val a: EagerLazyFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerLazyFirstGraph<!> {
  val value: EagerLazyFirstA
}

@Inject class FunctionFirstA(val deferred: () -> FunctionFirstB, val eager: FunctionFirstB)

@Inject class FunctionFirstB(val a: FunctionFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>FunctionFirstGraph<!> {
  val value: FunctionFirstA
}

@Inject
class EagerFunctionFirstA(
  val eager: EagerFunctionFirstB,
  val deferred: () -> EagerFunctionFirstB,
)

@Inject class EagerFunctionFirstB(val a: EagerFunctionFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerFunctionFirstGraph<!> {
  val value: EagerFunctionFirstA
}

@Inject class ProviderFirstA(val deferred: Provider<ProviderFirstB>, val eager: ProviderFirstB)

@Inject class ProviderFirstB(val a: ProviderFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>ProviderFirstGraph<!> {
  val value: ProviderFirstA
}

@Inject
class EagerProviderFirstA(
  val eager: EagerProviderFirstB,
  val deferred: Provider<EagerProviderFirstB>,
)

@Inject class EagerProviderFirstB(val a: EagerProviderFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerProviderFirstGraph<!> {
  val value: EagerProviderFirstA
}

@Inject
class SuspendFunctionFirstA(
  val deferred: suspend () -> SuspendFunctionFirstB,
  val eager: SuspendFunctionFirstB,
)

@Inject class SuspendFunctionFirstB(val a: SuspendFunctionFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>SuspendFunctionFirstGraph<!> {
  val value: SuspendFunctionFirstA
}

@Inject
class EagerSuspendFunctionFirstA(
  val eager: EagerSuspendFunctionFirstB,
  val deferred: suspend () -> EagerSuspendFunctionFirstB,
)

@Inject class EagerSuspendFunctionFirstB(val a: EagerSuspendFunctionFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerSuspendFunctionFirstGraph<!> {
  val value: EagerSuspendFunctionFirstA
}

@Inject
class SuspendProviderFirstA(
  val deferred: SuspendProvider<SuspendProviderFirstB>,
  val eager: SuspendProviderFirstB,
)

@Inject class SuspendProviderFirstB(val a: SuspendProviderFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>SuspendProviderFirstGraph<!> {
  val value: SuspendProviderFirstA
}

@Inject
class EagerSuspendProviderFirstA(
  val eager: EagerSuspendProviderFirstB,
  val deferred: SuspendProvider<EagerSuspendProviderFirstB>,
)

@Inject class EagerSuspendProviderFirstB(val a: EagerSuspendProviderFirstA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerSuspendProviderFirstGraph<!> {
  val value: EagerSuspendProviderFirstA
}

@Inject
class RepeatedMixedA(
  val firstDeferred: Lazy<RepeatedMixedB>,
  val firstEager: RepeatedMixedB,
  val secondDeferred: Provider<RepeatedMixedB>,
  val secondEager: RepeatedMixedB,
)

@Inject class RepeatedMixedB(val a: RepeatedMixedA)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>RepeatedMixedGraph<!> {
  val value: RepeatedMixedA
}

@Inject class LazyFirstSelf(val deferred: Lazy<LazyFirstSelf>, val eager: LazyFirstSelf)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>LazyFirstSelfGraph<!> {
  val value: LazyFirstSelf
}

@Inject class EagerFirstSelf(val eager: EagerFirstSelf, val deferred: () -> EagerFirstSelf)

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>EagerFirstSelfGraph<!> {
  val value: EagerFirstSelf
}
