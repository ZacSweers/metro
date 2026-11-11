// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// The factory's supertype is in scope only because an enclosing class inherits it.
abstract class Bases {
  interface BaseFactory<G> {
    fun create(@Provides value: String): G
  }
}

abstract class MoreBases : Bases()

class Outer : Bases() {
  @DependencyGraph
  interface AppGraph {
    val value: String

    @DependencyGraph.Factory fun interface Factory : BaseFactory<AppGraph>
  }
}

// The enclosing class inherits the base transitively.
class TransitiveOuter : MoreBases() {
  class Middle {
    @DependencyGraph
    interface AppGraph {
      val value: String

      @DependencyGraph.Factory fun interface Factory : BaseFactory<AppGraph>
    }
  }
}

// The factory's supertype is nested in an enclosing class's companion object.
class CompanionOuter {
  companion object {
    interface CompanionFactory<G> {
      fun create(@Provides value: String): G
    }
  }

  @DependencyGraph
  interface AppGraph {
    val value: String

    @DependencyGraph.Factory fun interface Factory : CompanionFactory<AppGraph>
  }
}

fun box(): String {
  assertEquals("x", Outer.AppGraph.create("x").value)
  assertEquals("y", TransitiveOuter.Middle.AppGraph.create("y").value)
  assertEquals("z", CompanionOuter.AppGraph.create("z").value)
  return "OK"
}
