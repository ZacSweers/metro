// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Base {
  @OnGraphInit fun start(@Named("label") label: String) {}
}

// Parameter qualifiers aren't inherited, so an override repeats them like accessors do.
@DependencyGraph
interface <!QUALIFIER_OVERRIDE_MISMATCH!>AppGraph<!> : CloseableGraph, Base {
  @Named("label") @Provides fun label(): String = "label"

  @OnGraphInit override fun start(label: String) {}
}
