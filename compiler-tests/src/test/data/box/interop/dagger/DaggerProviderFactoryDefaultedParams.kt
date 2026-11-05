// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import dagger.Module
import dagger.Provides
import javax.inject.Named

interface BaseProps {
  val value: String
}

class ProvidedValue(val value: String)

@Module
class InstanceModule(private val prefix: String) {
  @Provides @Named("instance")
  fun instanceValue(@Named("base") base: BaseProps? = null): ProvidedValue =
    ProvidedValue(prefix + (base?.value ?: "missing"))

}

@Module
object ObjectModule {
  @Provides
  fun objectValue(base: BaseProps? = null): ProvidedValue =
    ProvidedValue(base?.value ?: "missing")
}

@Module
abstract class GenericModule<T : Any>(private val prefix: String) {
  @Provides
  fun genericValue(value: T? = null): String = prefix + (value?.toString() ?: "missing")
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Named
import javax.inject.Singleton

@Singleton
@Inject
class ScopedConsumer(@Named("instance") val value: ProvidedValue)

@Singleton
@DependencyGraph(bindingContainers = [ObjectModule::class])
interface AppGraph {
  @Named("instance") val instanceValue: ProvidedValue
  @Named("instance") val instanceProvider: Provider<ProvidedValue>
  val scopedConsumer: ScopedConsumer
  val objectValue: ProvidedValue
  val genericValue: String

  @Singleton @Provides @Named("base")
  fun qualifiedBase(): BaseProps = object : BaseProps {
    override val value = "qualified"
  }

  @Provides
  fun base(): BaseProps = object : BaseProps {
    override val value = "unqualified"
  }

  @Provides fun number(): Int = 42

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes instance: InstanceModule, @Includes generic: GenericModule<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(
    InstanceModule("instance:"),
    object : GenericModule<Int>("generic:") {},
  )
  assertEquals("instance:qualified", graph.instanceValue.value)
  assertNotSame(graph.instanceValue, graph.instanceValue)
  assertEquals("instance:qualified", graph.instanceProvider().value)
  assertEquals("instance:qualified", graph.scopedConsumer.value.value)
  assertSame(graph.scopedConsumer, graph.scopedConsumer)
  assertEquals("unqualified", graph.objectValue.value)
  assertEquals("generic:42", graph.genericValue)
  return "OK"
}
