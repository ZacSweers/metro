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
  val exampleClassFactory: ExampleClassFactory
  val exampleClass: <!INVALID_ASSISTED_BINDING!>ExampleClass<!>

  @Provides val string: String get() = "Hello, world!"
}
