package test

@SingleIn(AppScope::class) @Inject class B
@Inject class BWrapper(val b1: B, val b2: B)
@Inject class A(val bWrapper: BWrapper) {
  fun areEqual(): Boolean = bWrapper.b1 == bWrapper.b2
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
