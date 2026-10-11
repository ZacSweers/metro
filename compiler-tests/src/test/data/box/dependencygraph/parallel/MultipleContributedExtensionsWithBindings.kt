// PARALLEL_THREADS: 4

// MODULE: lib
abstract class GrandchildScope

@BindingContainer
@ContributesTo(GrandchildScope::class)
object GrandchildBindings {
  @Provides fun provideBoolean(): Boolean = true
}

@ContributesTo(GrandchildScope::class)
interface GrandchildAccessors {
  val external: Boolean
}

// MODULE: main(lib)
abstract class ChildScope1

abstract class ChildScope2

abstract class ChildScope3

// Each child needs its own remapper for the same generic classes.
abstract class GenericBase<T>(val value: T)

@Inject class NestedService<T>(val value: T)

@Inject class GenericService<T>(value: T, val nested: NestedService<T>) : GenericBase<T>(value)

// Extension 1
@Inject class Service1(val value: String)

@GraphExtension(ChildScope1::class)
interface Child1Graph : GrandchildGraph.Factory {
  val service: Service1
  val generic: GenericService<String>

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
interface Child2Graph : GrandchildGraph.Factory {
  val service: Service2
  val generic: GenericService<Int>

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
interface Child3Graph : GrandchildGraph.Factory {
  val service: Service3
  val generic: GenericService<Long>

  @Provides fun provideLong(): Long = 3L

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild3(): Child3Graph
  }
}

// Each child creates a separate grandchild graph. Preparation must discover its external
// contributions before analysis starts, including the contributed binding container.
@GraphExtension(GrandchildScope::class)
interface GrandchildGraph : GrandchildAccessors {
  val nested: NestedService<Boolean>

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchild(): GrandchildGraph
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
  assertEquals("child1", child1.generic.value)
  assertEquals("child1", child1.generic.nested.value)
  assertEquals(2, child2.generic.value)
  assertEquals(2, child2.generic.nested.value)
  assertEquals(3L, child3.generic.value)
  assertEquals(3L, child3.generic.nested.value)
  val grandchildren =
    listOf<GrandchildGraph>(
      child1.createGrandchild(),
      child2.createGrandchild(),
      child3.createGrandchild(),
    )
  for (grandchild in grandchildren) {
    assertTrue(grandchild.external)
    assertTrue(grandchild.nested.value)
  }
  return "OK"
}
