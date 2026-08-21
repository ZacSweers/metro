// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface Service

@ContributesBinding(AppScope::class, priority = 100)
@Inject
class FirstService : Service

@ContributesBinding(AppScope::class, priority = 100)
@Inject
class SecondService : Service

@DependencyGraph(AppScope::class)
interface <!DUPLICATE_BINDING!>AppGraph<!> {
  val service: Service
}
