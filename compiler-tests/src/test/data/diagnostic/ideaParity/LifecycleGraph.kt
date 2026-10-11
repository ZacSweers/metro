// ENABLE_SUSPEND_PROVIDERS
// METRO_JVM_ONLY
// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-lifecycle-AppGraph.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/lifecycle/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/lifecycle/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/lifecycle/AppGraph/Impl

package parity.lifecycle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.CloseableGraph
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.OnGraphClose
import dev.zacsweers.metro.OnGraphInit
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

class Config

@SingleIn(AppScope::class) @Inject class Database

@SingleIn(AppScope::class) @Inject class Migrations(val database: Database)

@Inject class Request

interface ShutdownHooks {
  @OnGraphClose fun closeDatabase(database: Database) {}
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph, ShutdownHooks {
  val database: Database

  @Provides suspend fun provideConfig(): Config = Config()

  // Only this callback requests Migrations, so its parameter keeps Migrations in the graph.
  @OnGraphInit fun migrate(migrations: Migrations) {}

  @OnGraphInit suspend fun load(config: suspend () -> Config) {}

  @OnGraphInit fun prepare(request: <!DESUGARED_PROVIDER_WARNING!>Provider<Request><!>) {}

  @OnGraphClose fun log() {}

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(): AppGraph
  }
}
