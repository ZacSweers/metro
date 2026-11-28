// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A scoped parent binding reaches the child through the parent graph's instance.
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface <!MULTIBINDING_CONFLICT!>AppGraph<!> {
  @SingleIn(AppScope::class) @Provides fun provideStrings(): Set<String> = setOf("parent")

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>

  @Provides @IntoSet fun provideChild(): String = "child"
}
