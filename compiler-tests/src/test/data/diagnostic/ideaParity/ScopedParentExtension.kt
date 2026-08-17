// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-extension-scoped-AppGraph.json
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
  val childGraph: ChildGraph

  // The parent also consumes the scoped binding, keeping parent-side reports aligned. A child-only
  // consumer would still work in the compiler through key reservation, which the IDE's independent
  // per-context seals do not mirror.
  val cache: Cache
}

// METRO_JVM_ONLY
