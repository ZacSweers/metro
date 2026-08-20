// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-precedence-explicitinject-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/precedence/explicitinject/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/precedence/explicitinject/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/precedence/explicitinject/AppGraph/Impl

package parity.precedence.explicitinject

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

@Inject class Thing

@DependencyGraph
interface AppGraph {
  val thing: Thing

  @Provides fun <!REDUNDANT_PROVIDES!>provideThing<!>(): Thing = Thing()
}

// METRO_JVM_ONLY
