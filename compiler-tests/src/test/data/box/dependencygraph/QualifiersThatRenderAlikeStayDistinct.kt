// Qualifier arguments keep their structure even when their rendered text matches.
@Qualifier annotation class Tags(val values: Array<String>)

@Qualifier annotation class Named(val value: String)

@Qualifier annotation class Pair(val first: String, val second: String)

@DependencyGraph
interface AppGraph {
  @Tags(["a"]) val tagA: String
  @Tags(["b"]) val tagB: String
  @Tags(["a", "b"]) val tagAB: String
  @Named("a\", \"b") val joinedName: String
  @Pair("a", "b") val pair: String

  @Provides @Tags(["a"]) fun tagA(): String = "tag a"
  @Provides @Tags(["b"]) fun tagB(): String = "tag b"
  @Provides @Tags(["a", "b"]) fun tagAB(): String = "tag a b"
  @Provides @Named("a\", \"b") fun joinedName(): String = "joined name"
  @Provides @Pair("a", "b") fun pair(): String = "pair"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("tag a", graph.tagA)
  assertEquals("tag b", graph.tagB)
  assertEquals("tag a b", graph.tagAB)
  assertEquals("joined name", graph.joinedName)
  assertEquals("pair", graph.pair)
  return "OK"
}
