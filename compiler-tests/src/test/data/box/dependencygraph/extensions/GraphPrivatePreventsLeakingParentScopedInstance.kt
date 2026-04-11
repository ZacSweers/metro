// Verifies that @GraphPrivate prevents accidentally leaking a parent graph's scoped instance
// to a child graph. Both parent and child provide a scoped CoroutineScope, and consumers in
// each graph should receive the correct one for their scope.

annotation class ChildScope

class CoroutineScope(val name: String)

@Inject
class ParentConsumer(val scope: CoroutineScope)

@Inject
class ChildConsumer(val scope: CoroutineScope)

@DependencyGraph(AppScope::class)
interface ParentGraph {
  @GraphPrivate
  @SingleIn(AppScope::class)
  @Provides
  fun provideCoroutineScope(): CoroutineScope = CoroutineScope("parent")

  val consumer: ParentConsumer

  fun createChild(): ChildGraph
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  @GraphPrivate
  @SingleIn(ChildScope::class)
  @Provides
  fun provideCoroutineScope(): CoroutineScope = CoroutineScope("child")

  val consumer: ChildConsumer
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals("parent", parent.consumer.scope.name)

  val child = parent.createChild()
  assertEquals("child", child.consumer.scope.name)

  return "OK"
}
