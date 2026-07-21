// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-composition-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/composition/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/composition/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/composition/AppGraph/Impl

package parity.composition

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides

interface ExternalDependencies {
  val endpoint: String
}

class Box<T>(val value: T)

@BindingContainer
class GenericBindings<T> {
  @Provides fun value(): T = error("supplied by the included container")

  @Provides fun box(value: T): Box<T> = Box(value)
}

@DependencyGraph
interface AppGraph {
  val endpoint: String
  val box: Box<Int>

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Includes dependencies: ExternalDependencies,
      @Includes bindings: GenericBindings<Int>,
    ): AppGraph
  }
}

// METRO_JVM_ONLY
