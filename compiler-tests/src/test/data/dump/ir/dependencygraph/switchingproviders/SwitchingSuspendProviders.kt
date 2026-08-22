// ENABLE_SWITCHING_PROVIDERS: true
// ENABLE_SUSPEND_PROVIDERS
// STATEMENTS_PER_INIT_FUN: 2

@SingleIn(AppScope::class)
@Inject
class SynchronousScoped

class DirectSuspendValue(val value: String)

@SingleIn(AppScope::class)
@Inject
class TransitiveSuspendValue(val direct: DirectSuspendValue)

@Inject
class SharedUnscopedSuspendValue(val direct: DirectSuspendValue)

@Inject
class FirstSharedConsumer(val shared: SharedUnscopedSuspendValue)

@Inject
class SecondSharedConsumer(val shared: SharedUnscopedSuspendValue)

@SingleIn(AppScope::class)
@Inject
class DeferredSuspendConsumer(val direct: SuspendProvider<DirectSuspendValue>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val synchronous: SynchronousScoped

  val deferred: DeferredSuspendConsumer

  suspend fun direct(): DirectSuspendValue

  suspend fun transitive(): TransitiveSuspendValue

  suspend fun firstShared(): FirstSharedConsumer

  suspend fun secondShared(): SecondSharedConsumer

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDirectSuspendValue(): DirectSuspendValue = DirectSuspendValue("value")
}
