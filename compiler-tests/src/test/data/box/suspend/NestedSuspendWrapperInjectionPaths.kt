// ENABLE_SUSPEND_PROVIDERS
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
var injectionPathComputations = 0

class InjectionPathValue(val index: Int)

class SynchronousInjectionPathValue(val value: String)

abstract class InjectionPathScope private constructor()

// The same deferred suspend binding flows through every supported injection path:
//
// provideValue() [suspend]
//     |
//     `~~> () -> SuspendLazy<InjectionPathValue>
//              |
//              +--> @Inject member
//              +--> @AssistedInject constructor
//              +--> @Provides parameter
//              +--> injected top-level function
//              `--> graph-local @Inject constructor
//
// The graph-local target also requests InjectionPathValue directly. SynchronousInjectionPathValue
// is requested directly, through `() -> T`, and through `() -> SuspendLazy<T>` to cover the same
// wrapper shapes without suspension. `~~>` marks a deferred edge.

class MemberInjectedTarget {
  @Inject lateinit var value: () -> SuspendLazy<InjectionPathValue>
}

@AssistedInject
class AssistedTarget(
  @Assisted val name: String,
  val value: () -> SuspendLazy<InjectionPathValue>,
) {
  @AssistedFactory
  interface Factory {
    fun create(name: String): AssistedTarget
  }
}

class Report(val index: Int)

@Inject
@SingleIn(InjectionPathScope::class)
class GraphLocalTarget(
  val direct: InjectionPathValue,
  val nested: () -> SuspendLazy<InjectionPathValue>,
  val synchronous: SynchronousInjectionPathValue,
  val synchronousProvider: () -> SynchronousInjectionPathValue,
)

@Inject
fun NestedInjectedFunction(
  value: () -> SuspendLazy<InjectionPathValue>
): () -> SuspendLazy<InjectionPathValue> = value

@DependencyGraph(scope = InjectionPathScope::class)
interface ExampleGraph {
  val assistedFactory: AssistedTarget.Factory

  val nestedInjectedFunction: NestedInjectedFunction

  fun inject(target: MemberInjectedTarget)

  suspend fun report(): Report

  suspend fun graphLocalTarget(): GraphLocalTarget

  suspend fun mixedStorageKinds(): String

  @Provides
  suspend fun provideValue(): InjectionPathValue {
    injectionPathComputations++
    return InjectionPathValue(injectionPathComputations)
  }

  @Provides
  suspend fun provideReport(value: () -> SuspendLazy<InjectionPathValue>): Report {
    return Report(value().value().index)
  }

  @Provides
  fun provideSynchronousValue(): SynchronousInjectionPathValue {
    return SynchronousInjectionPathValue("sync")
  }

  @Provides
  suspend fun provideMixedStorageKinds(
    nested: () -> SuspendLazy<SynchronousInjectionPathValue>,
    provider: () -> SynchronousInjectionPathValue,
  ): String {
    return "${provider().value}:${nested().value().value}"
  }
}

fun box(): String =
  runBlocking {
    injectionPathComputations = 0
    val graph = createGraph<ExampleGraph>()

    val memberTarget = MemberInjectedTarget()
    graph.inject(memberTarget)
    assertEquals(0, injectionPathComputations)
    assertEquals(1, memberTarget.value().value().index)

    val assistedTarget = graph.assistedFactory.create("name")
    assertEquals("name", assistedTarget.name)
    assertEquals(1, injectionPathComputations)
    assertEquals(2, assistedTarget.value().value().index)

    assertEquals(3, graph.report().index)

    assertEquals(4, graph.nestedInjectedFunction.invoke()().value().index)

    val graphLocalTarget = graph.graphLocalTarget()
    assertEquals(5, graphLocalTarget.direct.index)
    assertEquals("sync", graphLocalTarget.synchronous.value)
    assertEquals("sync", graphLocalTarget.synchronousProvider().value)
    assertEquals(5, injectionPathComputations)
    assertEquals(6, graphLocalTarget.nested().value().index)
    assertSame(graphLocalTarget, graph.graphLocalTarget())

    assertEquals("sync:sync", graph.mixedStorageKinds())

    "OK"
  }
