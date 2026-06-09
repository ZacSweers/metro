import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

@DependencyGraph(AppScope::class)
interface App {
  val string: String
  val int: Int
}

val counter = AtomicInt(0)

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@ContributesTo(AppScope::class)
interface Providers {
  // due to SingleIn this should only be called once and return `1`
  @Provides @SingleIn(AppScope::class) fun incr(): AtomicInt = counter.also { it.incrementAndFetch() }
  @Provides fun string(int: Int): String = "$int"
  @Provides fun int(incr: AtomicInt): Int = incr.load()
}

fun box(): String {
  val graph = createGraph<App>()
  assertEquals("1", graph.string)
  assertEquals("1", graph.int.toString())
  return "OK"
}
