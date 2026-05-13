// WITH_KI_ANVIL

import me.tatarka.inject.annotations.Inject

@Inject class Foo

@Inject class Consumer(val fooProvider: () -> Foo, val lazyFoo: Lazy<Foo>)

@DependencyGraph
interface ExampleGraph {
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val consumer = graph.consumer
  assertNotNull(consumer.fooProvider())
  assertTrue(consumer.fooProvider() is Foo)
  // Function provider should produce a fresh instance on each invocation
  assertTrue(consumer.fooProvider() !== consumer.fooProvider())
  // Lazy should memoize
  assertSame(consumer.lazyFoo.value, consumer.lazyFoo.value)
  return "OK"
}
