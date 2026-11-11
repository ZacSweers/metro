private val seen = mutableListOf<String>()

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides fun number(): Int = 3

  // A default applies only when the graph has no binding for the parameter.
  @OnGraphInit fun start(label: CharSequence = "default", number: Int = 0) { seen += "$label $number" }
}

fun box(): String {
  createGraph<AppGraph>()
  return if (seen == listOf("default 3")) "OK" else "Fail: $seen"
}
