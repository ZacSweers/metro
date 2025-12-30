@DependencyGraph
interface AppGraph {
  @Binds
  val Int.unusedBinding1: Number
  @Binds
  val Int.unusedBinding2: Number
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return "OK"
}
