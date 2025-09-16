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
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Foo
  }
}

fun box(): String {
  val unitGraph = createGraph<UnitGraph>()
  assertNotNull(unitGraph.foo)
  return "OK"
}