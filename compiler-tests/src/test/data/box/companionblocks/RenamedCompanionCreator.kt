// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// METRO_JVM_ONLY
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

@DependencyGraph
interface AppGraph {
  companion object {
    @JvmStatic @JvmName("manualInvoke") fun invoke(): String = "OK"
  }
}

fun box(): String {
  val graph: AppGraph = AppGraph()
  return AppGraph.Companion.invoke()
}
