// MERGED_SUPERTYPE_CHUNK_SIZE: 2

@ContributesTo(AppScope::class)
interface A {
  val a: String
}

@ContributesTo(AppScope::class)
interface B {
  val b: String
}

@ContributesTo(AppScope::class)
interface C {
  val c: String
}

@ContributesTo(AppScope::class)
interface D {
  val d: String
}

@ContributesTo(AppScope::class)
interface E {
  val e: String
}

@MergeContributionsInIr
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideA(): String = "OK"
}

fun box(): String {
  // Five contributions with chunk size 2 should produce three chunk classes (2 + 2 + 1).
  // The graph still resolves all contributed members.
  val graph = createGraph<AppGraph>()
  assertEquals("OK", (graph as A).a)
  assertEquals("OK", (graph as B).b)
  assertEquals("OK", (graph as C).c)
  assertEquals("OK", (graph as D).d)
  assertEquals("OK", (graph as E).e)
  return "OK"
}
