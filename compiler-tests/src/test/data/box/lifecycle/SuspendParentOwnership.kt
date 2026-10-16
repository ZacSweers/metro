// ENABLE_SUSPEND_PROVIDERS
// IGNORE_BACKEND: JS_IR

@file:OptIn(ExperimentalMetroApi::class)

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking as runCoroutine

class Events {
  val values = mutableListOf<String>()
  val entered = CompletableDeferred<Unit>()
  val resume = CompletableDeferred<Unit>()
}

@DependencyGraph
interface ParentGraph : SuspendCloseableGraph {
  val childFactory: ChildGraph.Factory
  val syncChild: SyncChild

  @OnGraphClose
  fun finish(events: Events) { events.values += "parent" }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): ParentGraph
  }
}

@GraphExtension
interface SyncChild : CloseableGraph {
  @OnGraphClose fun finish(events: Events) { events.values += "sync-child" }
}

@GraphExtension
interface ChildGraph : SuspendCloseableGraph {
  @OnGraphInit
  suspend fun initialize(events: Events, waitForInit: Boolean) {
    if (waitForInit) {
      events.entered.complete(Unit)
      events.resume.await()
    }
  }

  @OnGraphClose
  suspend fun finish(events: Events, @Named("close") failClose: Boolean) {
    events.values += "suspend-child"
    if (failClose) { error("failed") }
  }

  @GraphExtension.Factory
  fun interface Factory {
    suspend fun create(@Provides waitForInit: Boolean, @Provides @Named("close") failClose: Boolean): ChildGraph
  }
}

fun box(): String = runCoroutine {
  val events = Events()
  val parent = createGraphFactory<ParentGraph.Factory>().create(events)
  val cancelled = launch { parent.childFactory.create(true, false) }
  events.entered.await()
  cancelled.cancelAndJoin()
  val failed = parent.childFactory.create(false, true)
  assertFailsWith<IllegalStateException> { failed.close() }
  parent.childFactory.create(false, false)
  parent.syncChild
  parent.close()
  assertEquals(2, events.values.count { it == "suspend-child" })
  assertEquals(1, events.values.count { it == "sync-child" })
  assertEquals("parent", events.values.last())
  "OK"
}
