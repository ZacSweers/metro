private var provideIntCalls = 0
private var provideLongCalls = 0
private var provideDoubleCalls = 0

@DependencyGraph
interface AppGraph {
  @Provides
  fun provideInt(): Int {
    provideIntCalls++
    return 3
  }

  // Should result in no getter generated for provideInt() because it's simple inputs
  @Provides
  fun provideLong(int: Int, int2: Int): Long {
    provideLongCalls++
    return (int + int2).toLong()
  }

  // Should result in getter generated for provideLong() because it's non-simple inputs
  @Provides
  fun provideDouble(long: Long, long2: Long): Double {
    provideDoubleCalls++
    return (long + long2).toDouble()
  }

  val double: Double

  val reused: Reused
  val reused2: Reused
}

@Inject data class NonReused(val value: Float = 3f)

@Inject data class Reused(val nonReused: NonReused)

fun box(): String {
  provideIntCalls = 0
  provideLongCalls = 0
  provideDoubleCalls = 0

  val graph = createGraph<AppGraph>()

  fun <T> runAsserts(body: () -> T) {
    assertEquals(body(), body())
    assertNotSame(body(), body())
  }

  assertEquals(12.0, graph.double)
  assertEquals(4, provideIntCalls)
  assertEquals(2, provideLongCalls)
  assertEquals(1, provideDoubleCalls)

  assertEquals(12.0, graph.double)
  assertEquals(8, provideIntCalls)
  assertEquals(4, provideLongCalls)
  assertEquals(2, provideDoubleCalls)

  runAsserts(graph::reused)
  runAsserts(graph::reused2)

  return "OK"
}
