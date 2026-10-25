private val events = mutableListOf<String>()

abstract class ChildScope

// FIR doesn't merge contributions into graph extensions, so it can't see this supertype.
@ContributesTo(ChildScope::class)
interface ChildCloseable : CloseableGraph

@GraphExtension(ChildScope::class)
interface Child {
  @OnGraphClose fun stop() {
    events += "child"
  }

  @GraphExtension.Factory fun interface Factory {
    fun create(): Child
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val childFactory: Child.Factory

  @OnGraphClose fun stop() {
    events += "parent"
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.childFactory.create()
  graph.close()
  return if (events == listOf("child", "parent")) "OK" else "Fail: $events"
}
