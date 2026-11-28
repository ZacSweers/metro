// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// The child's set would drop the contributions it inherits from the parent.
@DependencyGraph(AppScope::class)
interface <!MULTIBINDING_CONFLICT, SUSPICIOUS_UNUSED_MULTIBINDING!>AppGraph<!> {
  @Provides @IntoSet fun provideParent(): String = "parent"

  @Provides @IntoMap @StringKey("parent") fun provideParentInt(): Int = 1

  fun child(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides fun provideStrings(): Set<String> = setOf("child")

  @Provides fun provideInts(): Map<String, Int> = mapOf("child" to 2)
}
