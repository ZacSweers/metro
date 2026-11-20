// RENDER_DIAGNOSTICS_FULL_TEXT

package test

// Ok
@DependencyGraph
interface GraphWithAbstractClass {
  @DependencyGraph.Factory
  abstract class Factory {
    abstract fun create(): GraphWithAbstractClass
  }
}

// Ok
@DependencyGraph
interface GraphWithInterface {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): GraphWithInterface
  }
}

// Ok
@DependencyGraph
interface GraphWithFunInterface {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(): GraphWithFunInterface
  }
}

@DependencyGraph
interface GraphWithEnumFactory {
  @DependencyGraph.Factory
  enum class <!METRO_DECLARATION_ERROR!>Factory<!> {
    THIS_IS_JUST_WRONG
  }
}

@DependencyGraph
interface GraphWithOpenFactory {
  @DependencyGraph.Factory
  open class <!METRO_DECLARATION_ERROR!>Factory<!> {
    fun create(): GraphWithOpenFactory {
      TODO()
    }
  }
}

@DependencyGraph
interface GraphWithFinalFactory {
  @DependencyGraph.Factory
  class <!METRO_DECLARATION_ERROR!>Factory<!> {
    fun create(): GraphWithFinalFactory {
      TODO()
    }
  }
}

@DependencyGraph
interface GraphWithSealedFactoryInterface {
  @DependencyGraph.Factory
  sealed interface <!METRO_DECLARATION_ERROR!>Factory<!> {
    fun create(): GraphWithSealedFactoryInterface
  }
}

@DependencyGraph
interface GraphWithSealedFactoryClass {
  @DependencyGraph.Factory
  sealed class <!METRO_DECLARATION_ERROR!>Factory<!> {
    abstract fun create(): GraphWithSealedFactoryClass
  }
}
