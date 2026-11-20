// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(AppScope::class)
interface <!DUPLICATE_BINDING!>ExampleGraph<!> {
  val exampleClass: ExampleClass
}

interface ExampleClass

@ContributesBinding(AppScope::class)
@Inject
class Impl1 : ExampleClass

@ContributesBinding(AppScope::class)
@Inject
class Impl2 : ExampleClass
