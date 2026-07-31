// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/mapkey/AppGraph/Impl

package parity.failures.mapkey

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey

interface Handler

@BindingContainer
object Handlers {
  @Provides @IntoMap @StringKey("same") fun first(): Handler = object : Handler {}

  @Provides @IntoMap @StringKey("same") fun second(): Handler = object : Handler {}
}

@DependencyGraph(bindingContainers = [Handlers::class])
interface AppGraph {
  val <!DUPLICATE_MAP_KEY!>handlers<!>: Map<String, Handler>
}

// METRO_JVM_ONLY
