@DependencyGraph(AppScope::class)
interface AppGraph

@DependencyGraph(Unit::class)
interface UnitGraph {
  val foo: Foo

  @Provides
  fun provideFoo(factory: Foo.Factory): Foo {
    return factory.create("UnitGraph")
  }
}

@Inject
class Foo(
  @Assisted str: String,
  bar: Bar // <-- this should fail because it doesn't exist in UnitGraph
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Foo
  }
}

interface Bar

@Inject
@ContributesBinding(AppScope::class)
class BarImpl : Bar

fun box(): String {
  val unitGraph = createGraph<UnitGraph>()
  assertNotNull(unitGraph.foo)
  return "OK"
}