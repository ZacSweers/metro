// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

interface ViewModel

interface BaseViewModel : ViewModel

@DependencyGraph(AppScope::class)
interface <!SUSPICIOUS_UNUSED_MULTIBINDING!>AppGraph<!> {
  val value: Map<KClass<*>, ViewModel>
}

@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ClassKey
@Inject
class Impl : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey
@Inject
class Impl2 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey
@Inject
class Impl3 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey
@Inject
class Impl4 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey
@Inject
class Impl5 : BaseViewModel
