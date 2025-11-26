@DependencyGraph
interface AppGraph {
  // Uses emptyMap() in code gen
  @Multibinds(allowEmpty = true)
  val ints: Map<String, Int>
}