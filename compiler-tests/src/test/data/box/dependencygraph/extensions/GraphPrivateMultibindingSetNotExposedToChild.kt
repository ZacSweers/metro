// @GraphPrivate multibinding set contributions in a parent are not exposed to child graphs
@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @GraphPrivate @Multibinds val parentStrings: Set<String>

  @GraphPrivate @Provides @IntoSet fun provideHello(): String = "hello"

  @Provides @IntoSet fun provideWorld(): String = "world"

  fun childGraph(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val childStrings: Set<String>
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(setOf("hello", "world"), parent.parentStrings)
  val child = parent.childGraph()
  assertEquals(setOf("world"), child.childStrings)
  return "OK"
}
