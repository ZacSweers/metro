// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import javax.inject.Inject

interface BaseProps {
  val value: String
}

class Encoder @Inject constructor(private val base: BaseProps? = null) {
  fun encode(): String = base?.value ?: "missing"
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface AppGraph {
  val encoder: Encoder

  @Provides fun provideBaseProps(): BaseProps = object : BaseProps {
    override val value = "base"
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("base", graph.encoder.encode())
  return "OK"
}
