// ENABLE_SUSPEND_PROVIDERS
// METRO_JVM_ONLY
// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-suspend-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/suspend/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/suspend/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/suspend/AppGraph/Impl

package parity.suspend

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

class Database

@Inject class Repository(val database: Database)

@Inject class DeferredRepository(val database: suspend () -> Database)

@DependencyGraph
interface AppGraph {
  suspend fun repository(): Repository

  val deferredRepository: DeferredRepository

  val database: suspend () -> Database

  @Provides suspend fun provideDatabase(): Database = Database()
}
