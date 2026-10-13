// Init callbacks run when the graph is created and close callbacks run once when it closes.

@SingleIn(AppScope::class)
@Inject
class Analytics {
  val events = mutableListOf<String>()
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val analytics: Analytics

  @OnGraphInit
  fun initialize(analytics: Analytics) {
    analytics.events += "init"
  }

  @OnGraphInit
  fun logCreated() {
    created = true
  }

  @OnGraphClose
  fun shutdown(analytics: Analytics) {
    analytics.events += "close"
  }
}

var created = false

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(created)
  assertEquals(listOf("init"), graph.analytics.events)
  graph.close()
  // Later calls return right away
  graph.close()
  assertEquals(listOf("init", "close"), graph.analytics.events)
  return "OK"
}
