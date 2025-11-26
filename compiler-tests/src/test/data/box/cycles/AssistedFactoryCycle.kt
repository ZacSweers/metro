@AssistedInject
class Foo(
  @Assisted nested: Boolean,
  factoryProvider: Provider<Factory>,
  factory: Factory,
) {
  val nestedFooViaProvider = if (nested) factoryProvider().create() else null
  val nestedFoo = if (nested) factory.create() else null

  @AssistedFactory
  interface Factory {
    fun create(nested: Boolean = false): Foo
  }
}

@DependencyGraph
interface CycleGraph {
  fun fooFactory(): Foo.Factory
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  val foo = cycleGraph.fooFactory().create(nested = true)
  assertNotNull(foo.nestedFooViaProvider)
  assertNotNull(foo.nestedFoo)
  assertNull(foo.nestedFooViaProvider?.nestedFooViaProvider)
  assertNull(foo.nestedFoo?.nestedFoo)
  return "OK"
}
