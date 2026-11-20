// RENDER_DIAGNOSTICS_FULL_TEXT

package test

interface BaseFactory1<T> {
  fun create1(): T
}

interface BaseFactory2<T> : BaseFactory1<T> {
  fun create2(): T
}

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  interface <!FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION, FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION!>Factory<!> : BaseFactory2<ExampleGraph>
}
