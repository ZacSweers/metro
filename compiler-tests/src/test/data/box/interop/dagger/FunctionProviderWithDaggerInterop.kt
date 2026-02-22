// ENABLE_DAGGER_INTEROP
// ENABLE_FUNCTION_PROVIDERS
package test

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Provider

@DependencyGraph
interface ExampleGraph {
  val consumer: Consumer
}

class Foo @Inject constructor()

class Consumer
@Inject
constructor(
  val functionProvider: () -> Foo,
  val javaxProvider: Provider<Foo>,
  val daggerLazy: Lazy<Foo>,
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val consumer = graph.consumer
  // Function provider should work
  assertNotNull(consumer.functionProvider())
  // Javax provider should work
  assertNotNull(consumer.javaxProvider.get())
  // Dagger lazy should work
  assertNotNull(consumer.daggerLazy.get())
  // All should return Foo instances
  assertTrue(consumer.functionProvider() is Foo)
  assertTrue(consumer.javaxProvider.get() is Foo)
  assertTrue(consumer.daggerLazy.get() is Foo)
  return "OK"
}
