package test

@SingleIn(AppScope::class) @Inject class B
@Inject class BWrapper(val b1: B, val b2: B)
interface C
@ContributesBinding(AppScope::class)
@Inject class CImpl(val bWrapper: BWrapper) : C {
  fun areEqual(): Boolean = bWrapper.b1 == bWrapper.b2
}
@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val a: C
}

fun box(): String {
  val appGraph = createGraph<ExampleGraph>()
  assertTrue(assertIs<CImpl>(appGraph.a).areEqual())
  return "OK"
}
