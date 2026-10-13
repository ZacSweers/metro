// A failing init callback fails graph creation. A failing close callback leaves the graph closed
// and skips the rest of its cleanup.

var closeAttempts = 0

@DependencyGraph
interface FailingInitGraph : CloseableGraph {
  @OnGraphInit
  fun fail() {
    error("init failed")
  }
}

@DependencyGraph
interface FailingCloseGraph : CloseableGraph {
  @OnGraphClose
  fun fail() {
    closeAttempts++
    error("close failed")
  }
}

fun box(): String {
  val initError = assertFailsWith<IllegalStateException> { createGraph<FailingInitGraph>() }
  assertEquals("init failed", initError.message)

  val graph = createGraph<FailingCloseGraph>()
  val closeError = assertFailsWith<IllegalStateException> { graph.close() }
  assertEquals("close failed", closeError.message)
  // Cleanup doesn't run again
  graph.close()
  assertEquals(1, closeAttempts)
  return "OK"
}
