// ENABLE_SUSPEND_PROVIDERS
// METRO_JVM_ONLY
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/suspend/failure/AppGraph/Impl

package parity.suspend.failure

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

class Database

@Inject class Repository(val database: Database)

@Inject
class ProviderRepository(
  <!SUSPEND_BINDING_WRAPPED_IN_PROVIDER!>val database: () -> Database<!>
)

@DependencyGraph
interface AppGraph {
  val <!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>repository<!>: Repository

  val providerRepository: ProviderRepository

  @Provides suspend fun provideDatabase(): Database = Database()
}
