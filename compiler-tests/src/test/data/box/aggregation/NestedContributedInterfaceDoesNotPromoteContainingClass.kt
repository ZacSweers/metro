// https://github.com/ZacSweers/metro/issues/2551
// A nested @ContributesTo interface is a graph supertype, but its containing class is not.
@ContributesBinding(AppScope::class)
@Inject
class FooImpl : Foo {
  override fun doFoo() = Unit

  @ContributesTo(AppScope::class)
  interface Component : FooProvider
}

interface Foo {
  fun doFoo()
}

interface FooProvider {
  fun foo(): Foo
}

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>() as FooProvider
  assertIs<FooImpl>(graph.foo())
  return "OK"
}
