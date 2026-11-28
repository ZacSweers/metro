// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Scoped parent contributions reach the child through the parent graph's instance.
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface <!MULTIBINDING_CONFLICT!>AppGraph<!> {
  val strings: Set<String>

  @SingleIn(AppScope::class) @Provides @IntoSet fun provideParent(): String = "parent"

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>

  @Provides fun provideStrings(): Set<String> = setOf("child")
}
