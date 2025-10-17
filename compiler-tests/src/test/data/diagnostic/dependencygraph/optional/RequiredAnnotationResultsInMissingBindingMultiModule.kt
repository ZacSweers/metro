// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_DEPENDENCY

// MODULE: lib
@Inject
class Example(val value: String? = null)

interface Base {
  val int: Int

  @Provides
  fun provideInt(@OptionalDependency long: Long? = null): Int = long?.toInt() ?: 3
}

// MODULE: main(lib)
@DependencyGraph
interface <!METRO_ERROR!>AppGraph<!> : Base {
  val example: Example
}
