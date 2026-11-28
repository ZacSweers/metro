// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface <!MULTIBINDING_CONFLICT, SUSPICIOUS_UNUSED_MULTIBINDING!>AppGraph<!> {
  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet fun provideParent(): String = "parent"

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>
}
