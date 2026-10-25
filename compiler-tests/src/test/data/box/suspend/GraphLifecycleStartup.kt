// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides suspend fun message(): String = "OK"

  @OnGraphInit suspend fun start(message: String) { result += message }

  @DependencyGraph.Factory fun interface Factory {
    suspend fun create(): AppGraph
  }
}

@BindingContainer
object Replacement {
  @Provides suspend fun message(): String = "D"
}

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit suspend fun start(id: Int) { result += id }

  @GraphExtension.Factory fun interface Factory {
    suspend fun create(@Provides id: Int): Child
  }
}

@DependencyGraph
interface Parent : Child.Factory {
  val childFactory: Child.Factory
}

@DependencyGraph interface DeferredGraph : CloseableGraph {
  @Provides suspend fun text(): String { reads++; return "deferred" }
  @OnGraphInit fun start(provider: suspend () -> String, lazy: SuspendLazy<String>) {
    later = provider
    memo = lazy
  }
}

@DependencyGraph interface Failing : CloseableGraph {
  @OnGraphInit suspend fun start() { throw startupFailure }
  @OnGraphClose fun stop() { error("unexpected cleanup") }
  @DependencyGraph.Factory fun interface Factory { suspend fun create(): Failing }
}

private var reads = 0
private lateinit var later: suspend () -> String
private lateinit var memo: SuspendLazy<String>
private val startupFailure = kotlin.coroutines.cancellation.CancellationException("startup")
private var result = ""

fun box(): String = runBlocking {
  createGraphFactory<AppGraph.Factory>().create()
  createDynamicGraphFactory<AppGraph.Factory>(Replacement).create()
  val parent = createGraph<Parent>()
  parent.childFactory.create(1)
  parent.create(2)
  check(result == "OKD12") { result }
  createGraph<DeferredGraph>()
  check(reads == 0)
  check(later() == memo.await())
  check(reads == 2)
  val failure = runCatching { createGraphFactory<Failing.Factory>().create() }.exceptionOrNull()
  check(failure === startupFailure)
  "OK"
}
