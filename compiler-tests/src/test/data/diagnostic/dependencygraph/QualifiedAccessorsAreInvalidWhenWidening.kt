// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

interface Parent1 {
  @Named("qualified") val prop: String
  @Named("qualified") fun function(): String
}

@DependencyGraph interface <!QUALIFIER_OVERRIDE_MISMATCH!>AppGraph<!> : Parent1 {
  override val prop: String
  override fun function(): String

  @Named("qualified") @Provides fun provideString(): String = "hello"
}
