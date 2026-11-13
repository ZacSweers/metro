// ENABLE_DAGGER_INTEROP
// METRO_JVM_ONLY

import dagger.BindsOptionalOf
import java.util.Optional

// An optional binding for a key wins over a generic provider that matches the same key. The
// generic provider still supplies the keys that have no optional binding.
class Value

class Other

@BindingContainer
interface Bindings {
  @BindsOptionalOf fun optionalValue(): Value

  companion object {
    @Provides fun value(): Value = Value()

    @Provides fun <T : Any> absent(): Optional<T> = Optional.empty()
  }
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val value: Optional<Value>
  val other: Optional<Other>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.value.isPresent)
  assertFalse(graph.other.isPresent)
  return "OK"
}
