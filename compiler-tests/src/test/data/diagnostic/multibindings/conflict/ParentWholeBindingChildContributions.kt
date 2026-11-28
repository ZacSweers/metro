// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// The child's contributions would be dropped in favor of the parent's set.
@DependencyGraph(AppScope::class)
interface <!MULTIBINDING_CONFLICT!>AppGraph<!> {
  @Provides fun provideStrings(): Set<String> = setOf("parent")

  @Provides fun provideInts(): Map<String, Int> = mapOf("parent" to 1)

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides @IntoSet fun provideChild(): String = "child"

  @Provides @IntoMap @StringKey("child") fun provideChildInt(): Int = 2
}
