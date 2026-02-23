// @GraphPrivate multibinding map contributions in a parent are not exposed to child graphs
@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @GraphPrivate @Multibinds val parentMap: Map<String, Int>

  @GraphPrivate @Provides @IntoMap @StringKey("a") fun provideA(): Int = 1

  @Provides @IntoMap @StringKey("b") fun provideB(): Int = 2

  fun childGraph(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val childMap: Map<String, Int>
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(mapOf("a" to 1, "b" to 2), parent.parentMap)
  val child = parent.childGraph()
  assertEquals(mapOf("b" to 2), child.childMap)
  return "OK"
}
