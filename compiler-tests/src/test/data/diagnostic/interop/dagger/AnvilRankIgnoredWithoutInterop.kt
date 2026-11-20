// WITH_ANVIL
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_ANVIL_INTEROP: false

package test

interface ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
object Impl1 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 10)
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface <!DUPLICATE_BINDING!>ExampleGraph<!> {
  val contributedInterface: ContributedInterface
}
