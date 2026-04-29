// ENABLE_DAGGER_INTEROP
package test

import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

// Regression repro for commit 85543469: assisted dagger.Lazy<T> params can be remapped to T,
// causing a ClassCastException when the factory receives a DaggerInteropDoubleCheck instance.
class AssistedLazyConsumer
@AssistedInject
constructor(@Assisted private val lazyString: Lazy<String>) {

  fun value(): String = lazyString.get()

  @AssistedFactory
  interface Factory {
    fun create(lazyString: Lazy<String>): AssistedLazyConsumer
  }
}

@DependencyGraph
interface ExampleGraph {
  val assistedLazyConsumerFactory: AssistedLazyConsumer.Factory
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val consumer = graph.assistedLazyConsumerFactory.create(Lazy { "test" })

  assertEquals("test", consumer.value())
  return "OK"
}
