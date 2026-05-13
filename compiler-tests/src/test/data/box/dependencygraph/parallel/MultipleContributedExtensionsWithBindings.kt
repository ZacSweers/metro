// PARALLEL_THREADS: 4

abstract class ChildScope1

abstract class ChildScope2

abstract class ChildScope3

// Extension 1
@Inject class Service1(val value: String)

@GraphExtension(ChildScope1::class)
interface Child1Graph {
  val service: Service1

  @Provides fun provideString(): String = "child1"

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild1(): Child1Graph
  }
}

// Extension 2
@Inject class Service2(val value: Int)

@GraphExtension(ChildScope2::class)
interface Child2Graph {
  val service: Service2

  @Provides fun provideInt(): Int = 2

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild2(): Child2Graph
  }
}

// Extension 3
@Inject class Service3(val value: Long)

@GraphExtension(ChildScope3::class)
interface Child3Graph {
  val service: Service3

  @Provides fun provideLong(): Long = 3L

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild3(): Child3Graph
  }
}

@DependencyGraph(AppScope::class) interface ParentGraph

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child1 = parent.createChild1()
  val child2 = parent.createChild2()
  val child3 = parent.createChild3()
  assertEquals("child1", child1.service.value)
  assertEquals(2, child2.service.value)
  assertEquals(3L, child3.service.value)
  return "OK"
}
