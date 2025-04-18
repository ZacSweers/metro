// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
abstract class ExampleGraph {
  abstract val int: Int

  @Provides var <!PROVIDES_ERROR!>provideInt<!>: Int = 0
}
