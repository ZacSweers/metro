// https://github.com/ZacSweers/metro/issues/3081
// MODULE: lib
// FILE: Graphs.kt
internal interface GraphValues {
  val greeting: String
  val count: Int
}

@DependencyGraph(AppScope::class)
internal interface AppGraph : GraphValues {
  val seed: Long

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides seed: Long): AppGraph
  }
}

@DependencyGraph(AppScope::class)
internal interface SimpleGraph : GraphValues

@ContributesTo(AppScope::class)
@BindingContainer
internal object Bindings {
  @Provides fun greeting(): String = "hi"

  @Provides fun count(): Int = 1
}

// MODULE: main()(lib)
// FILE: TestBindings.kt
@BindingContainer
class FakeCount {
  @Provides fun count(): Int = 2
}

@DependencyGraph(AppScope::class)
internal interface PlainTestGraph : GraphValues

// FILE: MemberProperties.kt
internal class MemberProperties {
  val factoryGraph = createDynamicGraphFactory<AppGraph.Factory>(FakeCount()).create(3L)
  val simpleGraph = createDynamicGraph<SimpleGraph>(FakeCount())
}

// FILE: MemberFunctions.kt
internal class MemberFunctions {
  fun factoryGraph(): AppGraph =
    createDynamicGraphFactory<AppGraph.Factory>(FakeCount()).create(4L)

  fun simpleGraph(): SimpleGraph = createDynamicGraph<SimpleGraph>(FakeCount())
}

// FILE: TopLevelProperties.kt
internal val topLevelFactoryGraph =
  createDynamicGraphFactory<AppGraph.Factory>(FakeCount()).create(5L)
internal val topLevelSimpleGraph = createDynamicGraph<SimpleGraph>(FakeCount())

// FILE: Box.kt
private fun assertDynamicValues(graph: GraphValues) {
  assertEquals("hi", graph.greeting)
  assertEquals(2, graph.count)
}

fun box(): String {
  val plainGraph = createGraph<PlainTestGraph>()
  assertEquals("hi", plainGraph.greeting)
  assertEquals(1, plainGraph.count)

  val properties = MemberProperties()
  assertDynamicValues(properties.factoryGraph)
  assertEquals(3L, properties.factoryGraph.seed)
  assertDynamicValues(properties.simpleGraph)

  val functions = MemberFunctions()
  val memberFactoryGraph = functions.factoryGraph()
  assertDynamicValues(memberFactoryGraph)
  assertEquals(4L, memberFactoryGraph.seed)
  assertDynamicValues(functions.simpleGraph())

  assertDynamicValues(topLevelFactoryGraph)
  assertEquals(5L, topLevelFactoryGraph.seed)
  assertDynamicValues(topLevelSimpleGraph)

  val factoryGraph = createDynamicGraphFactory<AppGraph.Factory>(FakeCount()).create(6L)
  assertDynamicValues(factoryGraph)
  assertEquals(6L, factoryGraph.seed)
  assertDynamicValues(createDynamicGraph<SimpleGraph>(FakeCount()))
  return "OK"
}
