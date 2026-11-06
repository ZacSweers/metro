// FORCE_PROVIDER_GETTERS: true

// A provider chain with one reader is inlined into its accessor instead of getting a getter for
// each link.

@Inject class C

@Inject class B(val c: C)

@Inject class A(val b: B)

@DependencyGraph
interface AppGraph {
  val a: Provider<A>
}
