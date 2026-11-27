// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-qualifiedclass-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/qualifiedclass/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/qualifiedclass/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/qualifiedclass/AppGraph/Impl

package parity.qualifiedclass

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Qualifier

@Qualifier annotation class Chosen

// The graph requests this class without its qualifier.
@Chosen @Inject class RequestedUnqualified

// The graph requests this class with its qualifier.
@Chosen @Inject class RequestedQualified

interface Repo

// The contributed binding requests this class without its qualifier.
@Chosen
@Inject
@ContributesBinding(AppScope::class)
class RealRepo : Repo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val requestedUnqualified: RequestedUnqualified
  @Chosen val requestedQualified: RequestedQualified
  @Chosen val repo: Repo
}

// METRO_JVM_ONLY
