// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Named

class Holder<T : Any> @Inject constructor(@Named("value") val value: T? = null)

class AssistedHolder @AssistedInject constructor(
  @Assisted val prefix: String,
  val value: String? = null,
) {
  @AssistedFactory
  interface Factory {
    fun create(prefix: String): AssistedHolder
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Named

@DependencyGraph
interface AppGraph {
  val stringHolder: Holder<String>
  val intHolderProvider: Provider<Holder<Int>>
  val assistedFactory: AssistedHolder.Factory

  @Named("value") @Provides fun provideString(): String = "bound"
  @Named("value") @Provides fun provideInt(): Int = 42
  @Provides fun provideUnqualifiedString(): String = "assisted"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("bound", graph.stringHolder.value)
  assertEquals(42, graph.intHolderProvider().value)
  val assisted = graph.assistedFactory.create("prefix")
  assertEquals("prefix", assisted.prefix)
  assertEquals("assisted", assisted.value)
  return "OK"
}
