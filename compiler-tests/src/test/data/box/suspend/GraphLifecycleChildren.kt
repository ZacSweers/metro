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
  @OnGraphClose fun stop() { events += "starting child" }
  @GraphExtension.Factory fun interface Factory { suspend fun create(): StartingChild }
}

@GraphExtension
interface SuspendStartingChild : SuspendCloseableGraph {
  @OnGraphInit suspend fun start() { suspendCoroutine<Unit> { pending = it } }
  @OnGraphClose suspend fun stop() { events += "suspend starting child" }
  @GraphExtension.Factory fun interface Factory { suspend fun create(): SuspendStartingChild }
}

@DependencyGraph interface ExternalParent : CloseableGraph {
  val supplied: SuspendChild
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides supplied: SuspendChild): ExternalParent }
}

@DependencyGraph
interface Parent : SuspendCloseableGraph {
  val child: Child
  val starting: StartingChild.Factory
  val suspendStarting: SuspendStartingChild.Factory
  val suspendChild: SuspendChild
  @OnGraphClose fun stop() { events += "parent" }
}

fun box(): String = runBlocking {
  closingTheParentClosesItsChildren()
  aFailingChildDoesNotStopParentCleanup()
  aClosedParentRejectsAChildStillStarting()
  "OK"
}

private suspend fun closingTheParentClosesItsChildren() {
  val parent = createGraph<Parent>()
  val first = parent.child
  val second = parent.child
  val other = parent.suspendChild
  // A graph passed in as an input keeps its owner.
  val external = createGraphFactory<ExternalParent.Factory>().create(other)
  check(external.supplied === other)
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

private suspend fun aFailingChildDoesNotStopParentCleanup() {
  val parent = createGraph<Parent>()
  parent.child
  fail = true
  check(runCatching { parent.close() }.exceptionOrNull()?.message == "child failed")
  fail = false
  // The parent's own callback still ran after the child failed.
  check(events.takeLast(2) == listOf("child", "parent"))
  parent.close()
  check(events.takeLast(2) == listOf("child", "parent"))
}

/** Starts [create] and returns a function that reports its outcome once it has one. */
private fun <T> start(create: suspend () -> T): () -> Result<T>? {
  var result: Result<T>? = null
  create.startCoroutine(object : Continuation<T> {
    override val context = EmptyCoroutineContext
    override fun resumeWith(outcome: Result<T>) { result = outcome }
  })
  return { result }
}

private suspend fun aClosedParentRejectsAChildStillStarting() {
  val parent = createGraph<Parent>()
  val factory = parent.starting
  val suspendFactory = parent.suspendStarting
  val outcome = start { factory.create() }
  val starting = checkNotNull(pending)
  val suspendOutcome = start { suspendFactory.create() }
  val suspendStarting = checkNotNull(pending)
  check(outcome() == null)
  check(suspendOutcome() == null)
  parent.close()
  // Each child finishes starting after its parent closed. Nothing else would close it, so it's
  // closed before its creator sees the failure.
  starting.resume(Unit)
  check(outcome()?.exceptionOrNull() is IllegalStateException)
  check(events.last() == "starting child") { events }
  suspendStarting.resume(Unit)
  check(suspendOutcome()?.exceptionOrNull() is IllegalStateException)
  check(events.last() == "suspend starting child") { events }
}
