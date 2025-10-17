interface MyType

@ContributesBinding(AppScope::class)
@Inject
class Impl1 : MyType

@Inject
class Impl2 : MyType

@ContributesTo(AppScope::class)
@BindingContainer
interface ContributedContainer {
  @Binds fun bindMyType(
    impl: Impl2
  ): MyType
}

@GraphExtension(scope = AppScope::class, excludes = [ContributedContainer::class])
interface ExampleGraph {
  val myType: MyType

  @GraphExtension.Factory @ContributesTo(Unit::class)
  interface Factory {
    fun createExampleGraph(): ExampleGraph
  }
}

@DependencyGraph(scope = Unit::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>().createExampleGraph()
  assertEquals(graph.myType.javaClass.simpleName, "Impl1")
  return "OK"
}
