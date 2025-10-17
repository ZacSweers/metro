// DONT_SORT_DECLARATIONS
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

  val objWithCycle: Any

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