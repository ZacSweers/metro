interface A {
  val isReal: Boolean
}

@Inject class B(a: A)

@Inject
class RealA(b: Lazy<B>) : A {
  override val isReal: Boolean get() = true
}

@ContributesBinding(Unit::class)
@Inject
class FakeA(b: B, realA: RealA) : A {
  override val isReal: Boolean get() = false
}

@DependencyGraph(Unit::class)
interface CycleGraph {
  val a: A
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  assertNotNull(cycleGraph.a)
  assertFalse(cycleGraph.a.isReal)
  return "OK"
}
