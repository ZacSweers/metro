interface ChildScope
interface Service

@Inject
@ContributesBinding(AppScope::class, priority = 100)
class ParentService : Service

@Inject
@ContributesBinding(ChildScope::class, priority = 1)
class LowPriorityChildService : Service

@Inject
@ContributesBinding(ChildScope::class, priority = 10)
class HighPriorityChildService : Service

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val service: Service

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<ParentService>(graph.service)
  assertIs<HighPriorityChildService>(graph.createChildGraph().service)
  return "OK"
}
