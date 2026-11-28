// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@Inject class Strings : Set<String> by setOf("whole")

@Inject class Ints : Map<String, Int> by mapOf("whole" to 1)

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Binds fun bindStrings(strings: Strings): Set<String>

  @Binds fun bindInts(ints: Ints): Map<String, Int>

  @Provides @IntoSet fun provideChild(): String = "child"

  @Provides @IntoMap @StringKey("child") fun provideChildInt(): Int = 2
}
