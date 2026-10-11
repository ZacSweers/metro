// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// The accessor is declared in an upstream module, so the error is reported on the graph.

// MODULE: lib
@ContributesTo(AppScope::class)
interface MissingAccessor {
  val number: Int
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface <!MISSING_BINDING!>AppGraph<!> {
  val message: String

  @Provides fun provideMessage(): String = "hi"
}
