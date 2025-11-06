// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_BINDING

@Inject
class Example(<!METRO_ERROR!>val value: String? = null<!>)

@DependencyGraph
interface AppGraph {
  val example: Example
  val int: Int

  @Provides
  fun provideInt(<!METRO_ERROR!>long: Long? = null<!>): Int = long?.toInt() ?: 3
}
