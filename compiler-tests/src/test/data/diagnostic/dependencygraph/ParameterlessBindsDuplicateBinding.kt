// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val target: Target

  @Binds fun bindTarget(): Target

  @Provides fun provideTarget(): Target = Target()
}

@Inject class Target
