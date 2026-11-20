// METRO_JVM_ONLY

package test

@SingleIn(AppScope::class)
@DependencyGraph
abstract class ParentGraph : ChildGraph.Factory {
  private var count: Int = 0

  @SingleIn(AppScope::class)
  @Provides
  fun provideInt(): Int = count++
}

@SingleIn(Unit::class)
@GraphExtension
interface ChildGraph {
  val int: () -> Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertSame(childGraph.int, childGraph.int)
  return "OK"
}
