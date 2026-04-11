// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class ChildScope private constructor()

@DependencyGraph(AppScope::class)
interface <!SUSPICIOUS_UNUSED_MULTIBINDING!>AppGraph<!> {
  val childGraph: ChildGraph
}

interface Base

@ContributesIntoSet(AppScope::class)
@Inject
class Impl1 : Base

@ContributesIntoSet(AppScope::class)
@Inject
class Impl2 : Base

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val items: Set<Base>
}
