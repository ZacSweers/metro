// MODULE: lib
@GraphExtension
interface ChildGraph {
  @GraphPrivate @Provides @IntoMap @StringKey("b") fun provideB(): Int = 2

  val childMap: Map<String, Int>

  fun grandChildGraph(): GrandChildGraph

}

@GraphExtension
interface GrandChildGraph {
  @GraphPrivate @Provides @IntoMap @StringKey("c") fun provideC(): Int = 3

  val grandChildMap: Map<String, Int>
}

// MODULE: main(lib)
@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph {
  @Multibinds(allowEmpty = true) val parentMap: Map<String, Int>

  @GraphPrivate @Provides @IntoMap @StringKey("a") fun provideA(): Int = 1

  fun childGraph(): ChildGraph
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(mapOf("a" to 1), parent.parentMap)

  val child = parent.childGraph()
  assertEquals(mapOf("b" to 2), child.childMap)

  val grandChild = child.grandChildGraph()
  assertEquals(mapOf("c" to 3), grandChild.grandChildMap)
  return "OK"
}
