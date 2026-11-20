// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

interface Parent1 {
  val string: String
}

interface Parent2 {
  @Named("qualified") val string: String
}

@DependencyGraph interface <!QUALIFIER_OVERRIDE_MISMATCH!>AppGraph<!> : Parent1, Parent2
