// ENABLE_DAGGER_INTEROP
// ENABLE_KCLASS_TO_CLASS_INTEROP
// https://github.com/ZacSweers/metro/issues/2755

// MODULE: consumer
package repro.consumer

import dagger.MapKey
import javax.inject.Inject
import javax.inject.Provider
import kotlin.reflect.KClass

interface Worker

interface ChildWorkerFactory<T : Worker> {
  fun create(): T
}

@MapKey annotation class WorkerKey(val value: KClass<out Worker>)

class AppWorkerFactory
@Inject
constructor(
  val creators: Map<Class<out Worker>, @JvmSuppressWildcards Provider<ChildWorkerFactory<*>>>
)

// MODULE: bindings(consumer)
package repro.bindings

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import javax.inject.Inject
import repro.consumer.ChildWorkerFactory
import repro.consumer.Worker
import repro.consumer.WorkerKey

class HelloWorker : Worker

class HelloWorkerFactory @Inject constructor() : ChildWorkerFactory<HelloWorker> {
  override fun create(): HelloWorker = HelloWorker()
}

@Module
abstract class WorkerModule {
  @Binds
  @IntoMap
  @WorkerKey(HelloWorker::class)
  abstract fun bindHello(factory: HelloWorkerFactory): ChildWorkerFactory<*>
}

// MODULE: main(consumer, bindings)
package repro.app

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import javax.inject.Provider
import repro.bindings.HelloWorker
import repro.bindings.WorkerModule
import repro.consumer.AppWorkerFactory

@DependencyGraph(bindingContainers = [WorkerModule::class])
interface AppGraph {
  val appWorkerFactoryProvider: Provider<AppWorkerFactory>
}

fun box(): String {
  val factory = createGraph<AppGraph>().appWorkerFactoryProvider.get()
  val childFactory = factory.creators.getValue(HelloWorker::class.java).get()
  assertIs<HelloWorker>(childFactory.create())
  return "OK"
}
