// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

abstract class AltScope private constructor()

interface ContributedInterface
interface OtherInterface

@ContributesBinding(AppScope::class, binding = binding<ContributedInterface>())
@ContributesBinding(AltScope::class, binding = binding<OtherInterface>())
@Inject
class Impl : ContributedInterface, OtherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

@DependencyGraph(scope = AltScope::class)
interface AltGraph {
  val <!MISSING_BINDING!>contributedInterface<!>: ContributedInterface
  val otherInterface: OtherInterface
}
