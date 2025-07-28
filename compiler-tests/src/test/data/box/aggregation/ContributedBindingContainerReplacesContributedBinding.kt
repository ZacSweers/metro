interface MyType

@ContributesBinding(AppScope::class)
@Inject
class Impl1 : MyType

@Inject
class Impl2 : MyType

@ContributesTo(AppScope::class, replaces = [Impl1::class])
@BindingContainer
interface ContributedContainer {
  @Binds fun bindMyType(
    impl: Impl2
  ): MyType
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val myType: MyType
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(graph.myType.javaClass.simpleName, "Impl2")
  return "OK"
}
