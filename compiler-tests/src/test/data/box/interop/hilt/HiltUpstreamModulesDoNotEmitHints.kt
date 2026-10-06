// MODULE: hiltLib
// ENABLE_HILT_KSP
// WITH_DAGGER
// DISABLE_METRO
// FILE: UpstreamModule.kt
package test

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class UpstreamModule {
  @Provides fun provideMessage(): String = "Hello upstream"
}

// MODULE: libA(hiltLib)
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// FILE: LibA.kt
package test

class LibA

// MODULE: libB(hiltLib)
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// FILE: LibB.kt
package test

class LibB

// MODULE: main(hiltLib, libA, libB)
// ENABLE_HILT_INTEROP
// ENABLE_DAGGER_INTEROP
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import dagger.Module
import dagger.Provides as DaggerProvides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import test.LibA
import test.LibB

@Module
@InstallIn(SingletonComponent::class)
class LocalModule {
  @DaggerProvides fun provideNumber(): Int = 42
}

@DependencyGraph(Singleton::class)
interface AppGraph {
  val message: String
  val number: Int
}

@MergeContributionsInIr
@DependencyGraph(Singleton::class)
interface IrMergedGraph {
  val message: String
  val number: Int
}

// https://github.com/ZacSweers/metro/issues/2909
fun box(): String {
  for (consumerClass in listOf(LibA::class.java, LibB::class.java, AppGraph::class.java)) {
    assertFailsWith<ClassNotFoundException> {
      Class.forName(
        "metro.hints.TestUpstreamModuleJavax_inject_SingletonKt",
        false,
        consumerClass.classLoader,
      )
    }
  }
  Class.forName("metro.hints.LocalModuleJavax_inject_SingletonKt")

  val graph = createGraph<AppGraph>()
  assertEquals("Hello upstream", graph.message)
  assertEquals(42, graph.number)
  val irGraph = createGraph<IrMergedGraph>()
  assertEquals("Hello upstream", irGraph.message)
  assertEquals(42, irGraph.number)
  return "OK"
}
