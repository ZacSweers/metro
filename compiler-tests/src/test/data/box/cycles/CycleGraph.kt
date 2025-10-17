/*
 A ← (E ← D ← B ← C ← Provider<A>, Lazy<A>), (B ← C ← Provider<A>, Lazy<A>)
 */

@Inject class A(val b: B, val e: E)

@Inject class B(val c: C)

@Suppress("MEMBERS_INJECT_WARNING")
@Inject
class C(val aProvider: Provider<A>) {
  @Inject lateinit var aLazy: Lazy<A>
  @Inject lateinit var aLazyProvider: Provider<Lazy<A>>
}

@Inject class D(val b: B)

@Inject class E(val d: D)

@DependencyGraph
interface CycleGraph {
  fun a(): A

  fun c(): C

  fun childCycleGraph(): ChildCycleGraph.Factory

  @Provides
  private fun provideObjectWithCycle(obj: Provider<Any>): Any {
    return "object"
  }
}

@GraphExtension
interface ChildCycleGraph {
  val a: A

  val obj: Any

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildCycleGraph
  }
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()

  // providerIndirectionCycle
  val a = cycleGraph.a()
  val c = cycleGraph.c()
  assertNotNull(c.aProvider())
  assertNotNull(a.b.c.aProvider())
  assertNotNull(a.e.d.b.c.aProvider())

  // lazyIndirectionCycle
  assertNotNull(c.aLazy.value)
  assertNotNull(a.b.c.aLazy.value)
  assertNotNull(a.e.d.b.c.aLazy.value)

  // graphExtensionIndirectionCycle
  val parent = createGraph<CycleGraph>()
  val childCycleGraph = parent.childCycleGraph().create()
  val childA = childCycleGraph.a
  assertNotNull(childA.b.c.aProvider())
  assertNotNull(childA.e.d.b.c.aProvider())
  assertNotNull(childCycleGraph.obj)

  return "OK"
}
