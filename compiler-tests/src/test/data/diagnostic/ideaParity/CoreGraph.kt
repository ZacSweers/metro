// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-core-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/core/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/core/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/core/AppGraph/Impl
// DESUGARED_PROVIDER_SEVERITY: NONE

package parity.core

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides

interface Api

interface MissingDependency

@Inject class Engine

@Inject class RealApi(val engine: Engine) : Api

class OptionalValue(val missing: MissingDependency?)

@BindingContainer
interface CoreBindings {
  @Binds fun bindApi(impl: RealApi): Api

  companion object {
    @Provides @Named("base") fun name(): String = "metro"

    @Provides fun optionalValue(missing: MissingDependency? = null): OptionalValue =
      OptionalValue(missing)
  }
}

@Inject
class Consumer(
  val api: Api,
  @param:Named("base") val name: String,
  val engineProvider: Provider<Engine>,
  val engineLazy: Lazy<Engine>,
  val optionalValue: OptionalValue,
)

@DependencyGraph(bindingContainers = [CoreBindings::class])
interface AppGraph {
  val consumer: Consumer
}

// METRO_JVM_ONLY
