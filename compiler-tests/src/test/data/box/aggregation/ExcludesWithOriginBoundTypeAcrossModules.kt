// Regression test for https://github.com/ZacSweers/metro/discussions/2644
// Same as ExcludesWithOriginBoundType but with the contribution in a separate module,
// matching the original report.

// MODULE: lib
interface Foo

abstract class RealFoo : Foo

@Origin(RealFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedRealFoo : Foo {
  override fun toString() = "real"
}

@Inject
@ContributesBinding(AppScope::class)
class FakeFoo : Foo {
  override fun toString() = "fake"
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class, excludes = [RealFoo::class])
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.foo.toString())
  return "OK"
}
