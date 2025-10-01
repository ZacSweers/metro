// https://github.com/ZacSweers/metro/pull/761
/*
Foo  <---------------|
|- Bar.Factory       |
    |- Baz.Factory   |
        |- Foo ------|
 */
@DependencyGraph
interface CycleGraph {
  val foo: Foo
}

@Inject
class Foo(barFactory: Bar.Factory)

@AssistedInject
class Bar(
  @Assisted str: String,
  bazFactory: Baz.Factory,
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Bar
  }
}

@AssistedInject
class Baz(
  @Assisted str: String,
  foo: Foo,
) {
  @AssistedFactory
  interface Factory {
    fun create(str: String): Baz
  }
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  assertNotNull(cycleGraph.foo)
  return "OK"
}
