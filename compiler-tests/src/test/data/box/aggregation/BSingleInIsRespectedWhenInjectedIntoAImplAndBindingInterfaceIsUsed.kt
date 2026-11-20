package test

@SingleIn(AppScope::class) @Inject class B
interface A
@ContributesBinding(AppScope::class)
@Inject class AImpl(val b1: B, val b2: B): A {
  fun areEqual(): Boolean = b1 == b2
}
@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val a: A
}

fun box(): String {
  val appGraph = createGraph<ExampleGraph>()
  assertTrue(assertIs<AImpl>(appGraph.a).areEqual())
  return "OK"
}
