// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DEFAULT

// A provider's optional parameter can't hide a required parameter for the same missing type.
interface Missing

@DependencyGraph
interface OptionalFirstGraph {
  val value: Int

  @Provides
  fun provideValue(
    optional: Missing = error("unused"),
    <!MISSING_BINDING!>required: Missing<!>,
  ): Int = required.hashCode()
}

@DependencyGraph
interface RequiredFirstGraph {
  val value: Int

  @Provides
  fun provideValue(
    <!MISSING_BINDING!>required: Missing<!>,
    optional: Missing = error("unused"),
  ): Int = required.hashCode()
}
