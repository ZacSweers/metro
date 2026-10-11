// Contributed interfaces can bring their own callbacks. Callbacks inherited more than once, like
// through a diamond, run once. An override runs instead of the declaration it overrides.

val events = mutableListOf<String>()

interface BaseLifecycle {
  @OnGraphInit
  fun baseInit() {
    events += "base init"
  }

  @OnGraphInit
  fun overridden() {
    events += "original"
  }
}

interface LeftLifecycle : BaseLifecycle

interface RightLifecycle : BaseLifecycle {
  @OnGraphInit
  override fun overridden() {
    events += "override"
  }
}

@ContributesTo(AppScope::class)
interface AnalyticsLifecycle {
  @OnGraphInit
  fun initializeAnalytics() {
    events += "analytics init"
  }

  @OnGraphClose
  fun closeAnalytics() {
    events += "analytics close"
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph : LeftLifecycle, RightLifecycle, CloseableGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(listOf("analytics init", "base init", "override"), events.sorted())
  graph.close()
  assertEquals("analytics close", events.last())
  assertEquals(4, events.size)
  return "OK"
}
