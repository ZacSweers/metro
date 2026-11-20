// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

class Thing {
  @Inject lateinit var string: String
}

interface Parent1 {
  fun injectThing(thing: Thing)
}

interface Parent2 {
  fun injectThing(@Named("qualified") thing: Thing)
}

@DependencyGraph interface <!QUALIFIER_OVERRIDE_MISMATCH!>AppGraph<!> : Parent1, Parent2
