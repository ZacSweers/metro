import kotlin.test.assertEquals
import java.util.concurrent.atomic.AtomicInteger

@DependencyGraph(AppScope::class)
interface App {
  val string: String
  val int: Int
}

var counter = AtomicInteger(0)

@ContributesTo(AppScope::class)
interface Providers {
  // due to SingleIn this should only be called once and return `1`
  @Provides @SingleIn(AppScope::class) fun incr(): AtomicInteger = counter.also { counter.incrementAndGet() }
  @Provides fun string(int: Int): String = "$int"
  @Provides fun int(incr: AtomicInteger): Int = incr.get()
}

fun box(): String {
  val graph = createGraph<App>()
  assertEquals("1", graph.string)
  assertEquals("1", graph.int.toString())
  return "OK"
}
