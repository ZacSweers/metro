// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@DependencyGraph
interface StaticGraph {
  val text: String
  @Provides fun provideText(): String = "OK"
  companion object : StaticGraph by createGraph()
}
fun box(): String {
  assertEquals("OK", StaticGraph.text)
  assertEquals("OK", createGraph<StaticGraph>().text)
  assertEquals("OK", StaticGraph().text)
  return "OK"
}
