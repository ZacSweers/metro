// RENDER_DIAGNOSTICS_FULL_TEXT
// LANGUAGE: +ContextParameters
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
context(@OptionalDependency <!FUNCTION_INJECT_ERROR!>string<!>: String)
fun App() {
  // ...
}
