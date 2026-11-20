package test

@SingleIn(AppScope::class) @Inject class B
@Inject class A(val b1: B, val b2: B) {
  fun areEqual(): Boolean = b1 == b2
}
@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val a: A
}

fun box(): String {
  val appGraph = createGraph<ExampleGraph>()
  assertTrue(appGraph.a.areEqual())
  return "OK"
}
