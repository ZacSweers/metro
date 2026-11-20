// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  fun inject(<!SUSPICIOUS_MEMBER_INJECT_FUNCTION!>target<!>: ExampleClass)
}

class ExampleClass <!SUGGEST_CLASS_INJECTION!>@Inject<!> constructor()
