// IGNORE_BACKEND: JS_IR

@file:OptIn(ExperimentalMetroApi::class)

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Cleanup {
  val entered = CountDownLatch(1)
  val resume = CountDownLatch(1)
  val completed = AtomicInteger()
}

@DependencyGraph
interface ExampleGraph : CloseableGraph {
  @OnGraphClose
  fun finish(cleanup: Cleanup) {
    cleanup.entered.countDown()
    check(cleanup.resume.await(10, TimeUnit.SECONDS))
    cleanup.completed.incrementAndGet()
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides cleanup: Cleanup): ExampleGraph
  }
}

fun box(): String {
  val cleanup = Cleanup()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(cleanup)
  val first = thread { graph.close() }
  check(cleanup.entered.await(10, TimeUnit.SECONDS))
  try {
    graph.close()
    assertEquals(0, cleanup.completed.get())
  } finally {
    cleanup.resume.countDown()
    first.join(10000)
  }
  assertFalse(first.isAlive)
  assertEquals(1, cleanup.completed.get())
  graph.close()
  assertEquals(1, cleanup.completed.get())
  return "OK"
}
