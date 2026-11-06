// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import javax.inject.Inject
import javax.inject.Singleton

interface BaseProps {
  val value: String
}

class Prefix @Inject constructor() {
  val value = "p:"
}

@Singleton
class Encoder @Inject constructor(
  private val prefix: Prefix,
  private val base: BaseProps? = null,
) {
  fun encode(): String = prefix.value + (base?.value ?: "missing")
}

class RepoA @Inject constructor(val encoder: Encoder)

class RepoB @Inject constructor(val encoder: Encoder)

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Inject
import javax.inject.Singleton

@Singleton class Dep @Inject constructor()

class RealBaseProps @Inject constructor(val dep: Dep) : BaseProps {
  override val value = "base"
}

@Singleton
@DependencyGraph
interface AppGraph {
  val repoA: RepoA
  val repoB: RepoB

  @Singleton @Provides fun provideBaseProps(real: RealBaseProps): BaseProps = real
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("p:base", graph.repoA.encoder.encode())
  assertSame(graph.repoA.encoder, graph.repoB.encoder)
  return "OK"
}
