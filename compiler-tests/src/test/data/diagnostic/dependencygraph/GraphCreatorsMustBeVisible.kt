// RENDER_DIAGNOSTICS_FULL_TEXT

package test

// Ok
@DependencyGraph
abstract class GraphWithImplicitPublicFactory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): GraphWithImplicitPublicFactory
  }
}

// Ok
@DependencyGraph
abstract class GraphWithPublicFactory {
  @DependencyGraph.Factory
  public interface Factory {
    fun create(): GraphWithPublicFactory
  }
}

// Ok
@DependencyGraph
abstract class GraphWithInternalFactory {
  @DependencyGraph.Factory
  internal interface Factory {
    fun create(): GraphWithInternalFactory
  }
}

@DependencyGraph
abstract class GraphWithProtectedFactory {
  @DependencyGraph.Factory
  <!METRO_DECLARATION_VISIBILITY_ERROR!>protected<!> interface Factory {
    fun create(): GraphWithProtectedFactory
  }
}

@DependencyGraph
abstract class GraphWithPrivateFactory {
  @DependencyGraph.Factory
  <!METRO_DECLARATION_VISIBILITY_ERROR!>private<!> interface Factory {
    fun create(): GraphWithPrivateFactory
  }
}
