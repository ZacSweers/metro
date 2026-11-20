// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun ReadInt(int: Int): Int = int

@Inject
fun ReadProvider(int: () -> Int): Int = int()

@Inject
fun ReadLazy(int: Lazy<Int>): Int {
  // A single invocation reuses its lazy value.
  val first = int.value
  assertEquals(first, int.value)
  assertEquals(first, int.value)
  return int.value
}

@DependencyGraph
abstract class DeferredGraph {
  abstract val readInt: ReadInt
  abstract val readProvider: ReadProvider
  abstract val readLazy: ReadLazy

  private var count = 0

  @Provides private fun provideInt(): Int = count++
}

fun box(): String {
  val direct = createGraph<DeferredGraph>().readInt
  assertEquals(listOf(0, 1, 2), List(3) { direct.invoke() })

  val provider = createGraph<DeferredGraph>().readProvider
  assertEquals(listOf(0, 1, 2), List(3) { provider.invoke() })

  // Each invocation gets a fresh lazy argument.
  val lazy = createGraph<DeferredGraph>().readLazy
  assertEquals(listOf(0, 1, 2), List(3) { lazy.invoke() })
  return "OK"
}
