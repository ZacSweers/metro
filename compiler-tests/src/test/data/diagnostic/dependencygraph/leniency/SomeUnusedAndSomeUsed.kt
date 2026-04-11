// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface AppGraph {
  @Provides fun unusedString(int: Int): String {
    return int.toString()
  }

  @Binds val <!MISSING_BINDING!>Int<!>.unusedBinding: Number

  val number: Number
}
