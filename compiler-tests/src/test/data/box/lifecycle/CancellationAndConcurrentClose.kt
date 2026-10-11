// ENABLE_SUSPEND_PROVIDERS
// IGNORE_BACKEND: JS_IR

@file:OptIn(ExperimentalMetroApi::class)

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking as runCoroutine

class Cleanup {
  val entered = CompletableDeferred<Unit>()
  val resume = CompletableDeferred<Unit>()
  var completed = 0
}

@DependencyGraph
interface ExampleGraph : SuspendCloseableGraph {
  @OnGraphClose
  suspend fun finish(cleanup: Cleanup) {
    cleanup.entered.complete(Unit)
    cleanup.resume.await()
    cleanup.completed++
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides cleanup: Cleanup): ExampleGraph
  }
}

fun box(): String = runCoroutine {
  val cleanup = Cleanup()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(cleanup)
  val first = launch { graph.close() }
  cleanup.entered.await()
  graph.close()
  assertEquals(0, cleanup.completed)
  first.cancel()
  cleanup.resume.complete(Unit)
  first.cancelAndJoin()
  assertEquals(1, cleanup.completed)
  graph.close()
  assertEquals(1, cleanup.completed)
  "OK"
}
