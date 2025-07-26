/*
Foo  <---------------|
|- Bar.Factory       |
    |- Qux.Factory   |
        |- Foo ------|
 */
@DependencyGraph
interface CycleGraph {
  val foo: Foo
}

@Inject
class Foo(barFactory: Bar.Factory)

@Inject
class Bar(
  @Assisted str: String,
  quxFactory: Qux.Factory,
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Bar
  }
}

@Inject
class Qux(
  @Assisted str: String,
  foo: Foo,
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Qux
  }
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  assertNotNull(cycleGraph.foo)
  return "OK"
}
