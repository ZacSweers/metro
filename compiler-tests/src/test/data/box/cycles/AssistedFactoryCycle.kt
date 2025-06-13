@Inject
class Foo(
  @Assisted nested: Boolean,
  factoryProvider: Provider<Factory>,
  factoryLazy: Lazy<Factory>,
  factory: Factory,
) {
  val nestedFooViaProvider = if (nested) factoryProvider().create() else null
  val nestedFooViaLazy = if (nested) factoryLazy.value.create() else null
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
  assertNotNull(foo.nestedFooViaLazy)
  assertNotNull(foo.nestedFoo)
  assertNull(foo.nestedFooViaProvider?.nestedFooViaProvider)
  assertNull(foo.nestedFooViaLazy?.nestedFooViaLazy)
  assertNull(foo.nestedFoo?.nestedFoo)
  return "OK"
}
