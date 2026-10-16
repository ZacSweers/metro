// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true
@DependencyGraph
interface EmptyGraph
@DependencyGraph
interface InputGraph {
  val text: String
  @DependencyGraph.Factory fun interface Factory {
    fun build(@Provides text: String): InputGraph
  }
}
