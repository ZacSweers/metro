@DependencyGraph
interface AppGraph {
  @Provides
  fun unusedString(int: Int): String {
    return int.toString()
  }

  @Binds val Int.unusedBinding: Number
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return "OK"
}
