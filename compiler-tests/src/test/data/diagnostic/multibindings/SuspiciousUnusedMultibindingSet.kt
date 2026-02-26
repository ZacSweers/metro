// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

interface ViewModel

interface BaseViewModel : ViewModel

@DependencyGraph(AppScope::class)
interface <!SUSPICIOUS_UNUSED_MULTIBINDING!>AppGraph<!> {
  val value: Set<ViewModel>
}

@ContributesIntoSet(AppScope::class, binding = binding<ViewModel>())
@Inject
class Impl : BaseViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class Impl2 : BaseViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class Impl3 : BaseViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class Impl4 : BaseViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class Impl5 : BaseViewModel
