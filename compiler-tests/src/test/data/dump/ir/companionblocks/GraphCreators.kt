// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@DependencyGraph
interface EmptyGraph
@DependencyGraph
interface InputGraph {
  val text: String
  @DependencyGraph.Factory fun interface Factory {
    fun build(@Provides text: String): InputGraph
  }
}
