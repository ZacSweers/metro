// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DISABLED

// MODULE: lib
@Inject
class Example(val value: String? = null)

interface Base {
  val int: Int

  @Provides
  fun provideInt(long: Long? = null): Int = long?.toInt() ?: 3
}

// MODULE: main(lib)
@DependencyGraph
interface <!MISSING_BINDING!>AppGraph<!> : Base {
  val example: Example
}
