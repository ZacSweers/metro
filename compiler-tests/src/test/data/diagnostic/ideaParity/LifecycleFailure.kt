// ENABLE_SUSPEND_PROVIDERS
// METRO_JVM_ONLY
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/lifecycle/failure/AppGraph/Impl

package parity.lifecycle.failure

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.CloseableGraph
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.OnGraphClose
import dev.zacsweers.metro.OnGraphInit
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

class Database

@Inject class Request

@SingleIn(AppScope::class) @Inject class Cache

@DependencyGraph(AppScope::class)
interface <!GRAPH_INIT_SUSPEND_REQUIRED!>AppGraph<!> : CloseableGraph {
  @Provides suspend fun provideDatabase(): Database = Database()

  @OnGraphInit
  fun migrate(<!LIFECYCLE_CALLBACK_SUSPEND_REQUIRED, UNSCOPED_LIFECYCLE_PARAMETER!>database: Database<!>) {}

  @OnGraphInit fun start(<!UNSCOPED_LIFECYCLE_PARAMETER!>request: Request<!>) {}

  @OnGraphClose fun stop(<!CLOSE_ONLY_BINDING!>cache: Cache<!>) {}
}
