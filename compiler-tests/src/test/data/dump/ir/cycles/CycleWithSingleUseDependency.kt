// DONT_SORT_DECLARATIONS
// X is only used by B, which is deferred to break the A <-> B cycle. X's factory is inlined.

@Inject class X

@Inject class A(val b: B)

@Inject class B(val a: () -> A, val x: X)

@DependencyGraph
interface CycleWithSingleUseDependencyGraph {
  val a: A
}
