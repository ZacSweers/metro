// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface ExampleGraph {
  @<!OPT_IN_USAGE!>GraphPrivate<!> fun <!PRIVATE_BINDING_ERROR!>someFunction<!>(): String = "hello"
}
