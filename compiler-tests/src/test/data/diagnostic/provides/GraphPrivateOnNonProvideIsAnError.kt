// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface ExampleGraph {
  @GraphPrivate fun <!PRIVATE_BINDING_ERROR!>someFunction<!>(): String = "hello"
}
