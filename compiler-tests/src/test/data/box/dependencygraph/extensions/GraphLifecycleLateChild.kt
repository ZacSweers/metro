private val events = mutableListOf<String>()

@GraphExtension
interface Child : CloseableGraph {
  // The parent closes while this child is still starting.
  @OnGraphInit fun start(parent: Parent) {
    events += "start"
    parent.close()
  }

  @OnGraphClose fun stop() {
    events += "stop"
  }
}

@DependencyGraph
interface Parent : CloseableGraph {
  val child: Child
}

fun box(): String {
  val parent = createGraph<Parent>()
  val failure = runCatching { parent.child }.exceptionOrNull()
  if (failure !is IllegalStateException) return "Fail: $failure"
  // The child finished starting, so it's closed before its creator sees the failure.
  return if (events == listOf("start", "stop")) "OK" else "Fail: $events"
}
