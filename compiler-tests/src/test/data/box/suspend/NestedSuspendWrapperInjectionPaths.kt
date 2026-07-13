// ENABLE_SUSPEND_PROVIDERS
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
@file:Suppress("DESUGARED_PROVIDER_WARNING", "OPT_IN_USAGE")
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

var injectionPathComputations = 0

class InjectionPathValue(val index: Int)

class SynchronousInjectionPathValue(val value: String)

abstract class InjectionPathScope private constructor()

class MemberInjectedTarget {
  @Inject lateinit var value: Provider<SuspendLazy<InjectionPathValue>>
}

class AssistedTarget
@AssistedInject
constructor(
  @Assisted val name: String,
  val value: Provider<SuspendLazy<InjectionPathValue>>,
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
  val nested: Provider<SuspendLazy<InjectionPathValue>>,
  val synchronous: SynchronousInjectionPathValue,
  val synchronousProvider: Provider<SynchronousInjectionPathValue>,
)

@Inject
fun NestedInjectedFunction(
  value: Provider<SuspendLazy<InjectionPathValue>>
): Provider<SuspendLazy<InjectionPathValue>> = value

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
  suspend fun provideReport(value: Provider<SuspendLazy<InjectionPathValue>>): Report {
    return Report(value().value().index)
  }

  @Provides
  fun provideSynchronousValue(): SynchronousInjectionPathValue {
    return SynchronousInjectionPathValue("sync")
  }

  @Provides
  suspend fun provideMixedStorageKinds(
    nested: Provider<SuspendLazy<SynchronousInjectionPathValue>>,
    provider: Provider<SynchronousInjectionPathValue>,
  ): String {
    return "${provider().value}:${nested().value().value}"
  }
}

fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
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
