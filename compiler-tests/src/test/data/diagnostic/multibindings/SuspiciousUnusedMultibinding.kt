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
@ClassKey(Impl::class)
@Inject
class Impl : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey(Impl2::class)
@Inject
class Impl2 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey(Impl3::class)
@Inject
class Impl3 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey(Impl4::class)
@Inject
class Impl4 : BaseViewModel

@ContributesIntoMap(AppScope::class)
@ClassKey(Impl5::class)
@Inject
class Impl5 : BaseViewModel
