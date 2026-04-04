// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// MAX_IR_ERRORS_COUNT: 5

@DependencyGraph
interface AppGraph {
  // 10 missing bindings, but only report the first five
  @Named("1") val <!MISSING_BINDING!>int1<!>: Int
  @Named("2") val <!MISSING_BINDING!>int2<!>: Int
  @Named("3") val <!MISSING_BINDING!>int3<!>: Int
  @Named("4") val <!MISSING_BINDING!>int4<!>: Int
  @Named("5") val <!MISSING_BINDING!>int5<!>: Int
  @Named("6") val int6: Int
  @Named("7") val int7: Int
  @Named("8") val int8: Int
  @Named("9") val int9: Int
  @Named("10") val int10: Int
}
