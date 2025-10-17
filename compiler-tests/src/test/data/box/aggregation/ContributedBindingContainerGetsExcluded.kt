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

@DependencyGraph(scope = AppScope::class, excludes = [ContributedContainer::class])
interface ExampleGraph {
  val myType: MyType
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(graph.myType.javaClass.simpleName, "Impl1")
  return "OK"
}
