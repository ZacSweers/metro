// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/issues/2727
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

class Foo

@Singleton
@BindingContainer
class Leaves @Inject constructor(private val fooProvider: Provider<Foo>) {
  @Provides fun foo(): Foo = fooProvider.get()
}

@DependencyGraph
interface AppGraph {
  val foo: Foo

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes leaves: Leaves): AppGraph
  }
}

fun box(): String {
  val foo = Foo()
  val leaves = Leaves(Provider { foo })
  val graph = createGraphFactory<AppGraph.Factory>().create(leaves)
  assertSame(foo, graph.foo)
  return "OK"
}
