// Verify that dagger.Lazy<T> can be satisfied by a binding that provides
// a subtype of T. PR 883 improved dagger interop to allow wrapping Provider subtypes.
// ENABLE_DAGGER_INTEROP

import dagger.Lazy

interface Base
class Derived @Inject constructor() : Base

@DependencyGraph
interface AppGraph {
  val lazyBase: Lazy<Base>

  @Binds fun bind(derived: Derived): Base
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val lazyBase = graph.lazyBase
  if (lazyBase.get() !is Derived) return "Fail: not Derived"
  return "OK"
}
