// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-extension-scoped-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-parity-extension-scoped-AppGraph-Impl-ChildGraphImpl.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/extension/scoped/AppGraph/Impl
// CHECK_REPORTS: keys-populated/parity/extension/scoped/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/extension/scoped/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/extension/scoped/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/extension/scoped/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/extension/scoped/AppGraph/Impl/ChildGraphImpl

package parity.extension.scoped

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

abstract class CacheScope private constructor()

@Inject class Config

@SingleIn(CacheScope::class) @Inject class Cache(val config: Config)

@Inject class ChildThing(val cache: Cache)

@GraphExtension
interface ChildGraph {
  val childThing: ChildThing
}

@SingleIn(CacheScope::class)
@DependencyGraph
interface AppGraph {
  // Only the child consumes Cache, so the parent validates it purely through the child's
  // upward key reservation.
  val childGraph: ChildGraph
}

// METRO_JVM_ONLY
