// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val validFoo: ValidFoo

  @Binds fun bindValidFoo(): ValidFoo

  @Provides fun <!REDUNDANT_PROVIDES!>provideValidFoo<!>(): ValidFoo = ValidFoo()
}

@Inject class ValidFoo
