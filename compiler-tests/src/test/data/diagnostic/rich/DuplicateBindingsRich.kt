// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY
// DIAGNOSTICS_RENDER_MODE: RICH

// Duplicate bindings show dimmed signatures and squiggles.

@DependencyGraph
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val string: String

  @Provides fun provideString1(): String = "1"
  @Provides fun provideString2(): String = "2"
}
