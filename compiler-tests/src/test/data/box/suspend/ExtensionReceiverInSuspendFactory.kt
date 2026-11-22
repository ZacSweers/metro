// ENABLE_SUSPEND_PROVIDERS

private var syncCalls = 0
private var suspendCalls = 0

@DependencyGraph
interface Graph {
  val provider: suspend () -> String

  @Provides suspend fun next(): Int = ++suspendCalls

  @Provides fun Int.twice(): Long = this * 2L

  @Provides fun prefix(): Char = 'a' + syncCalls++

  @Provides fun Char.label(value: Long): String = "$this$value"
}

fun box(): String {
  syncCalls = 0
  suspendCalls = 0
  val provider = createGraph<Graph>().provider
  assertEquals(0, syncCalls)
  assertEquals(0, suspendCalls)

  return runBlocking {
    assertEquals("a2", provider())
    assertEquals("b4", provider())
    "OK"
  }
}
