// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private var pending: Continuation<Unit>? = null
private val events = mutableListOf<String>()
private var fail = false

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit fun start() { events += "start" }
  @OnGraphClose fun stop() {
    events += "child"
    check(!fail) { "child failed" }
  }
}

@GraphExtension
interface SuspendChild : SuspendCloseableGraph {
  @OnGraphClose suspend fun stop() { events += "suspend child" }
}

@GraphExtension
interface StartingChild : CloseableGraph {
  @OnGraphInit suspend fun start() { suspendCoroutine<Unit> { pending = it } }
  @GraphExtension.Factory fun interface Factory { suspend fun create(): StartingChild }
}

@DependencyGraph interface ExternalParent : CloseableGraph {
  val supplied: Child
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides supplied: Child): ExternalParent }
}

@DependencyGraph
interface Parent : SuspendCloseableGraph {
  val child: Child
  val starting: StartingChild.Factory
  val suspendChild: SuspendChild
  @OnGraphClose fun stop() { events += "parent" }
}

fun box(): String = runBlocking {
  closingTheParentClosesItsChildren()
  aFailingChildStopsParentCleanup()
  aClosedParentRejectsAChildStillStarting()
  "OK"
}

private suspend fun closingTheParentClosesItsChildren() {
  val parent = createGraph<Parent>()
  val first = parent.child
  val second = parent.child
  val other = parent.suspendChild
  // A graph passed in as an input keeps its owner.
  val external = createGraphFactory<ExternalParent.Factory>().create(first)
  check(external.supplied === first)
  external.close()
  check(events == listOf("start", "start"))
  // Closing a child directly removes it from its parent.
  first.close()
  check(events == listOf("start", "start", "child"))
  // Children close before the parent's own callbacks.
  parent.close()
  check(events.takeLast(3).toSet() == setOf("child", "suspend child", "parent"))
  check(events.last() == "parent")
  second.close()
  other.close()
  check(events.size == 6)
}

private suspend fun aFailingChildStopsParentCleanup() {
  val parent = createGraph<Parent>()
  parent.child
  fail = true
  check(runCatching { parent.close() }.exceptionOrNull()?.message == "child failed")
  parent.close()
  check(events.last() == "child")
}

private suspend fun aClosedParentRejectsAChildStillStarting() {
  val parent = createGraph<Parent>()
  val factory = parent.starting
  var result: Result<StartingChild>? = null
  val create: suspend () -> StartingChild = { factory.create() }
  create.startCoroutine(object : Continuation<StartingChild> {
    override val context = EmptyCoroutineContext
    override fun resumeWith(outcome: Result<StartingChild>) { result = outcome }
  })
  check(result == null)
  parent.close()
  checkNotNull(pending).resume(Unit)
  check(result?.exceptionOrNull() is IllegalStateException)
}
