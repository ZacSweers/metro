// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass <!SUGGEST_CLASS_INJECTION!>@AssistedInject<!> constructor(
  @Assisted val count: Int,
  val message: String,
)

@AssistedFactory
interface ExampleClassFactory {
  fun create(count: Int): ExampleClass
}

@DependencyGraph
interface ExampleGraph {
  // The omission of ExampleClassFactory from accessors is intentional, prevents
  // regression of https://github.com/ZacSweers/metro/issues/538 caused by existence
  // of other dependents short-circuiting the check on roots
  val exampleClass: <!INVALID_ASSISTED_BINDING!>ExampleClass<!>

  @Provides val string: String get() = "Hello, world!"
}
