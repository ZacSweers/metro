private val events = mutableListOf<String>()

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit fun start() {
    events += "start"
  }

  @OnGraphClose fun stop() {
    events += "stop"
  }
}

// Parent isn't closeable, so it doesn't track Child. Whoever creates Child closes it.
@DependencyGraph
interface Parent {
  val child: Child
}

fun box(): String {
  val child = createGraph<Parent>().child
  child.close()
  child.close()
  return if (events == listOf("start", "stop")) "OK" else "Fail: $events"
}
