// ENABLE_SUSPEND_PROVIDERS

// A long chain mixing eager and deferred suspend dependencies:
//
// provideString() [suspend]
//     |
//     v
// provideInt(String) [transitively suspend] -----------> intValue()
//     | \
//     |  `~~> Sink.intProviderFn
//     v
// provideLong(Int) [scoped, transitively suspend] -----> longValue()
//     | \
//     |  `~~> Sink.longProvider
//     `~~> provideDouble(suspend () -> Long) [suspend] --> Sink.doubleScalar
//               |
//               `~~> provideShort(suspend () -> Double) [sync] --> Sink.short
//
// `-->` is an eager dependency. `~~>` is a deferred `suspend () -> T` edge, which stops suspend
// propagation.

@Inject
class Sink(
  val short: Short,
  val longProvider: suspend () -> Long,
  val intProviderFn: suspend () -> Int,
  val doubleScalar: Double,
)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun sink(): Sink

  @Provides suspend fun provideString(): String = "hallo"

  @Provides fun provideInt(s: String): Int = s.length

  @Provides
  @SingleIn(AppScope::class)
  fun provideLong(i: Int): Long {
    longComputations++
    return i.toLong()
  }

  @Provides suspend fun provideDouble(l: suspend () -> Long): Double = l().toDouble()

  @Provides fun provideShort(d: suspend () -> Double): Short = 7

  // Force multi-ref on Int and Long
  suspend fun intValue(): Int

  suspend fun longValue(): Long
}

var longComputations = 0

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return runBlocking {
    val sink = graph.sink()

    // Short inlined via the deferred `suspend () -> Double` wrapper
    assertEquals(7, sink.short.toInt())
    // Double = Long.toDouble() = "hallo".length = 5
    assertEquals(5.0, sink.doubleScalar)
    // Deferred wrappers resolve on demand
    assertEquals(5, sink.intProviderFn())
    assertEquals(5L, sink.longProvider())
    // Multi-ref accessors resolve through the shared nested suspend factories
    assertEquals(5, graph.intValue())
    assertEquals(5L, graph.longValue())
    // Long is scoped — SuspendDoubleCheck must have computed it exactly once across all of the
    // above (sink construction, longProvider(), longValue(), and Double's dep)
    assertEquals(1, longComputations)
    "OK"
  }
}
