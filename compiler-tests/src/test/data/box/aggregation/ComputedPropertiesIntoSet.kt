// https://github.com/ZacSweers/metro/issues/808

// MODULE: lib1
@ContributesTo(AppScope::class)
interface I1 {
  @Provides
  @IntoSet
  val value1: Int
    get() = 1
}


// MODULE: lib2
@ContributesTo(AppScope::class)
interface I2 {
  @Provides
  @IntoSet
  val value2: Int
    get() = 2
}

// MODULE: main(lib1, lib2)
@DependencyGraph(AppScope::class)
interface Graph {
  val values: Set<Int>
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals(setOf(1, 2), graph.values)
  return "OK"
}