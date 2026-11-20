// WITH_ANVIL
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

interface ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object Impl1 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
object Impl2 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object Impl3 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface <!DUPLICATE_BINDING!>ExampleGraph<!> {
  val contributedInterface: ContributedInterface
}
