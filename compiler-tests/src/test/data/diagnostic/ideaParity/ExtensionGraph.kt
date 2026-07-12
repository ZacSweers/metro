// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-extension-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/extension/AppGraph/Impl
// CHECK_REPORTS: keys-populated/parity/extension/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/extension/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/extension/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/extension/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/extension/AppGraph/Impl/ChildGraphImpl

package parity.extension

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

interface ParentApi

@Inject class ChildValue(val api: ParentApi)

@GraphExtension
interface ChildGraph {
  val childValue: ChildValue
}

@DependencyGraph
interface AppGraph {
  val childGraph: ChildGraph

  @Provides fun parentApi(): ParentApi = object : ParentApi {}
}

// METRO_JVM_ONLY
