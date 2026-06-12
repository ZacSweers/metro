// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// DIAGNOSTICS_CONSOLE: RICH
// MAX_COMPILER_VERSION: 2.4.19

// Rich console rendering of duplicate bindings: dimmed signatures with squiggles + ANSI styling,
// escaped in the golden via AnsiMarkup. Capped to <2.4.20 because the rich golden naming lives in
// Metro's legacy diagnostics handler.

@DependencyGraph
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val string: String

  @Provides fun provideString1(): String = "1"
  @Provides fun provideString2(): String = "2"
}
