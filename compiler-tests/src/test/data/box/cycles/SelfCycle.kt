import kotlin.test.*

@Suppress("MEMBERS_INJECT_WARNING")
@Inject
class S(val sProvider: Provider<S>) {
  @Inject lateinit var sLazy: Lazy<S>
}

@DependencyGraph
interface SelfCycleGraph {
  fun s(): S
}

fun box(): String {
  val selfCycleGraph = createGraph<SelfCycleGraph>()
  val s = selfCycleGraph.s()
  assertNotNull(s.sProvider())
  assertNotNull(s.sLazy.value)
  return "OK"
}