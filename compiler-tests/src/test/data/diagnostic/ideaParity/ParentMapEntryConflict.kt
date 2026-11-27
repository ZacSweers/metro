// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

package parity.failures.parentmapentry

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey

// Map entries can't be overridden. ChildGraph's entry with an inherited map key is a duplicate map
// key. Its String override is reported separately.

@GraphExtension
interface ChildGraph {
  val string: String
  val <!DUPLICATE_MAP_KEY!>values<!>: Map<String, String>

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "child"

  @Provides @IntoMap @StringKey("same") fun childValue(): String = "child"
}

@DependencyGraph
interface AppGraph {
  val string: String
  val values: Map<String, String>
  val childGraph: ChildGraph

  @Provides fun provideString(): String = "parent"

  @Provides @IntoMap @StringKey("same") fun appValue(): String = "app"
}

// METRO_JVM_ONLY
