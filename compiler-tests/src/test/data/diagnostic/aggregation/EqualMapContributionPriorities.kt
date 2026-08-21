// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface Service

@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = 100)
@Inject
class FirstService : Service

@StringKey("shared")
@ContributesIntoMap(AppScope::class, priority = 100)
@Inject
class SecondService : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!DUPLICATE_MAP_KEY!>services<!>: Map<String, Service>
}
