// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.allSupertypes
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.Ignore

class AggregationTest : MetroCompilerTest() {

  override val extraImports: List<String> = listOf("kotlin.reflect.*")

  @Test
  fun `contributing types are generated in fir`() {
    compile(
      source(
        """
        @ContributesTo(AppScope::class)
        interface ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface")
    }
  }

  @Test
  fun `contributing types are visible from another module`() {
    val firstResult =
      compile(
        source(
          """
          @ContributesTo(AppScope::class)
          interface ContributedInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface")
    }
  }

  @Test
  fun `ContributesBinding with implicit bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with implicit bound type - object`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        object Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with implicit bound type - additional scope`() {
    compile(
      source(
        """
        interface ContributedInterface

        abstract class LoggedInScope private constructor()

        @ContributesBinding(LoggedInScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class, additionalScopes = [LoggedInScope::class])
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with implicit bound type - from another module`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface

          @ContributesBinding(AppScope::class)
          @Inject
          class Impl : ContributedInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with implicit qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Named("named")
        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with specific bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesBinding(
          AppScope::class,
          binding<ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with multiple bound types`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesBinding(
          AppScope::class,
          binding<ContributedInterface>()
        )
        @ContributesBinding(
          AppScope::class,
          binding<AnotherInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
          val anotherInterface: AnotherInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
      val anotherInterface = graph.callProperty<Any>("anotherInterface")
      assertThat(anotherInterface).isNotNull()
      assertThat(anotherInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with specific qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesBinding(
          AppScope::class,
          binding<@Named("hello") ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("hello")
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with generic bound type`() {
    compile(
      source(
        """
        interface ContributedInterface<T>

        @ContributesBinding(
          AppScope::class,
          binding<ContributedInterface<String>>()
        )
        @Inject
        class Impl : ContributedInterface<String>

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface<String>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding with generic qualified bound type from another module`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface<T>

          @ContributesBinding(
            AppScope::class,
            binding<@Named("named") ContributedInterface<String>>()
          )
          @Inject
          class Impl : ContributedInterface<String>
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterface: ContributedInterface<String>
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with implicit bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with implicit bound type - object`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        object Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with implicit bound type - from another compilation`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface

          @ContributesIntoSet(AppScope::class)
          @Inject
          class Impl : ContributedInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with implicit qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Named("named")
        @ContributesIntoSet(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with specific bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesIntoSet(
          AppScope::class,
          binding<ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with specific qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesIntoSet(
          AppScope::class,
          binding<@Named("hello") ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("hello")
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with generic bound type`() {
    compile(
      source(
        """
        interface ContributedInterface<T>

        @ContributesIntoSet(
          AppScope::class,
          binding<ContributedInterface<String>>()
        )
        @Inject
        class Impl : ContributedInterface<String>

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface<String>>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet with generic qualified bound type from another module`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface<T>

          @ContributesIntoSet(
            AppScope::class,
            binding<@Named("named") ContributedInterface<String>>()
          )
          @Inject
          class Impl : ContributedInterface<String>
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterfaces: Set<ContributedInterface<String>>
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with implicit bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ClassKey(Impl::class)
        @ContributesIntoMap(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with implicit bound type - object`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ClassKey(Impl::class)
        @ContributesIntoMap(AppScope::class)
        object Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with implicit bound type - from another compilation`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface

          @ClassKey(Impl::class)
          @ContributesIntoMap(AppScope::class)
          @Inject
          class Impl : ContributedInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).isNotEmpty()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with implicit qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ClassKey(Impl::class)
        @Named("named")
        @ContributesIntoMap(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with specific bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesIntoMap(
          AppScope::class,
          binding<@ClassKey(Impl::class) ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with specific qualified bound type`() {
    compile(
      source(
        """
        interface ContributedInterface
        interface AnotherInterface

        @ContributesIntoMap(
          AppScope::class,
          binding<@ClassKey(Impl::class) @Named("hello") ContributedInterface>()
        )
        @Inject
        class Impl : ContributedInterface, AnotherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("hello")
          val contributedInterfaces: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with generic bound type`() {
    compile(
      source(
        """
        interface ContributedInterface<T>

        @ContributesIntoMap(
          AppScope::class,
          binding<@ClassKey(Impl::class) ContributedInterface<String>>()
        )
        @Inject
        class Impl : ContributedInterface<String>

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<KClass<*>, ContributedInterface<String>>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap with generic qualified bound type from another module`() {
    val firstResult =
      compile(
        source(
          """
          interface ContributedInterface<T>

          @ContributesIntoMap(
            AppScope::class,
            binding<@ClassKey(Impl::class) @Named("named") ContributedInterface<String>>()
          )
          @Inject
          class Impl : ContributedInterface<String>
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("named") val contributedInterfaces: Map<KClass<*>, ContributedInterface<String>>
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesTo can be repeated to contribute to multiple scopes in a downstream module`() {
    val previousCompilation =
      compile(
        source(
          """
          abstract class AltScope private constructor()
          abstract class ThirdScope private constructor()

          @ContributesTo(AppScope::class)
          @ContributesTo(AltScope::class)
          @ContributesTo(ThirdScope::class)
          interface ContributedInterface {
            @Provides
            fun provideValue(): String = "Hello, world!"
          }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val myVal: String
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val altVal: String
        }

        @DependencyGraph(scope = ThirdScope::class)
        interface ThirdGraph {
          val thirdVal: String
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = previousCompilation,
    ) {
      val appGraphClass = ExampleGraph
      val appGraph = appGraphClass.generatedImpl().createGraphWithNoArgs()
      appGraphClass.assertHasContributedSupertype("test.ContributedInterface")
      assertThat(appGraph.callProperty<String>("myVal")).isEqualTo("Hello, world!")

      val altGraphClass = classLoader.loadClass("test.AltGraph")
      val altGraph = altGraphClass.generatedImpl().createGraphWithNoArgs()
      altGraphClass.assertHasContributedSupertype("test.ContributedInterface", scope = "AltScope")
      assertThat(altGraph.callProperty<String>("altVal")).isEqualTo("Hello, world!")

      val thirdGraphClass = classLoader.loadClass("test.ThirdGraph")
      val thirdGraph = thirdGraphClass.generatedImpl().createGraphWithNoArgs()
      thirdGraphClass.assertHasContributedSupertype(
        "test.ContributedInterface",
        scope = "ThirdScope",
      )
      assertThat(thirdGraph.callProperty<String>("thirdVal")).isEqualTo("Hello, world!")
    }
  }

  /**
   * @param scope Represents which scope name class is expected. Each nested contribution class is
   *   suffixed with the scope it's contributing to
   *
   * ```
   * @ContributesBinding(AppScope::class) // This maps to MetroContributionToAppScope
   * @ContributesBinding(AltScope::class) // This maps to MetroContribution2ToAltScope
   * @Inject
   * class ContributingClass : SomeInterface
   * ```
   */
  private fun Class<*>.assertHasContributedSupertype(
    superTypeFqName: String,
    scope: String = "AppScope",
  ) {
    assertThat(allSupertypes().map { it.name })
      .containsExactly($$"$$superTypeFqName$MetroContributionTo$${scope}", superTypeFqName)
  }

  @Test
  fun `ContributesTo can be repeated to contribute to multiple scopes in a merging module`() {
    compile(
      source(
        """
        abstract class AltScope private constructor()

        @ContributesTo(AppScope::class)
        @ContributesTo(AltScope::class)
        interface ContributedInterface {
          @Provides
          fun provideValue(): String = "Hello, world!"
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val myVal: String
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val altVal: String
        }
        """
          .trimIndent()
      )
    ) {
      val appGraphClass = ExampleGraph
      val appGraph = appGraphClass.generatedImpl().createGraphWithNoArgs()
      appGraphClass.assertHasContributedSupertype("test.ContributedInterface")
      assertThat(appGraph.callProperty<String>("myVal")).isEqualTo("Hello, world!")

      val altGraphClass = classLoader.loadClass("test.AltGraph")
      val altGraph = altGraphClass.generatedImpl().createGraphWithNoArgs()
      altGraphClass.assertHasContributedSupertype("test.ContributedInterface", scope = "AltScope")
      assertThat(altGraph.callProperty<String>("altVal")).isEqualTo("Hello, world!")
    }
  }

  @Test
  fun `duplicate ContributesTo annotations are an error - scope only`() {
    compile(
      source(
        """
        @ContributesTo(AppScope::class)
        @ContributesTo(AppScope::class)
        interface ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:7:1 Duplicate `@ContributesTo` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:8:1 Duplicate `@ContributesTo` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - scope only - implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - scope only - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<ContributedInterface>())
        @ContributesBinding(AppScope::class, binding<ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - with qualifiers - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<@Named("1") ContributedInterface>())
        @ContributesBinding(AppScope::class, binding<@Named("1") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations with different qualifiers are ok - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<@Named("1") ContributedInterface>())
        @ContributesBinding(AppScope::class, binding<@Named("2") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterface1: ContributedInterface
          @Named("2") val contributedInterface2: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
      val contributedInterface2 = graph.callProperty<Any>("contributedInterface2")
      assertThat(contributedInterface2).isNotNull()
      assertThat(contributedInterface2.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations with different qualifiers are ok - mixed`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @ContributesBinding(AppScope::class, binding<@Named("2") ContributedInterface>())
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterface1: ContributedInterface
          @Named("2") val contributedInterface2: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
      val contributedInterface2 = graph.callProperty<Any>("contributedInterface2")
      assertThat(contributedInterface2).isNotNull()
      assertThat(contributedInterface2.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding annotations with different scopes and same bound types are ok`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Scope annotation class SecondScope

        @ContributesBinding(AppScope::class)
        @ContributesBinding(SecondScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = SecondScope::class)
        interface ExampleGraph2 {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
      val graph2 =
        classLoader.loadClass("test.ExampleGraph2").generatedImpl().createGraphWithNoArgs()
      val contributedInterface2 = graph2.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface2).isNotNull()
      assertThat(contributedInterface2.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `single instance of a type can be annotated with both @ContributesIntoSet and @ContributesBinding`() {
    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedSet: Set<ContributedInterface>
          val contributedInterface: SecondInterface
        }

        interface ContributedInterface
        interface SecondInterface

        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class, binding<SecondInterface>())
        @ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
        @Inject class Impl : ContributedInterface, SecondInterface
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      graph.callProperty<Set<Any>>("contributedSet").also { contributedSet ->
        assertThat(contributedSet.single()::class.qualifiedName).isEqualTo("test.Impl")
        assertThat(contributedSet.single())
          .isEqualTo(graph.callProperty<Any>("contributedInterface"))
      }
    }
  }

  @Test
  fun `implicit bound types use class qualifier - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterface1: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesBinding supports explicit bound type with class-level qualifier`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<ContributedInterface>())
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterface1: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - scope only - mix of explicit and implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<ContributedInterface>())
        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `binding as Nothing is an error - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<Nothing>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Explicit bound types should not be `Nothing` or `Nothing?`."
      )
    }
  }

  @Test
  fun `binding can be Any - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<Any>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding is not assignable - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<Unit>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Class test.Impl does not implement explicit bound type kotlin.Unit"
      )
    }
  }

  @Test
  fun `binding can be ancestor - ContributesBinding`() {
    compile(
      source(
        """
        interface BaseContributedInterface

        interface ContributedInterface : BaseContributedInterface

        @ContributesBinding(AppScope::class, binding<BaseContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val base: BaseContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val base = graph.callProperty<Any>("base")
      assertThat(base).isNotNull()
      assertThat(base.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `binding class must be injected - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesBinding` is only applicable to constructor-injected classes, assisted factories, or objects. Ensure test.Impl is injectable or a bindable object."
      )
    }
  }

  @Test
  fun `binding with no explicit bound type or supertypes is an error - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesBinding`-annotated class test.Impl has no supertypes to bind to."
      )
    }
  }

  @Test
  fun `binding assisted factory is ok - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @AssistedInject
        class Impl(@Assisted input: String) {
          @ContributesBinding(AppScope::class)
          @AssistedFactory
          fun interface Factory : ContributedInterface {
            fun create(input: String): Impl
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding must not be the same as the class - ContributesBinding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<Impl>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  @Test
  fun `binding with no supertypes and not Any is an error - ContributesBinding`() {
    compile(
      source(
        """
        @ContributesBinding(AppScope::class, binding<Impl>())
        @Inject
        class Impl

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: Impl.kt:7:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes for a single type in a merging module`() {
    compile(
      source(
        """
        abstract class AltScope private constructor()

        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @ContributesBinding(AltScope::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val altGraph = classLoader.loadClass("test.AltGraph").generatedImpl().createGraphWithNoArgs()
      val altContributedInterface = altGraph.callProperty<Any>("contributedInterface")
      assertThat(altContributedInterface).isNotNull()
      assertThat(altContributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes for a single type in a downstream module`() {
    val previousCompilation =
      compile(
        source(
          """
          abstract class AltScope private constructor()

          interface ContributedInterface

          @ContributesBinding(AppScope::class)
          @ContributesBinding(AltScope::class)
          @Inject
          class Impl : ContributedInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = previousCompilation,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val altGraph = classLoader.loadClass("test.AltGraph").generatedImpl().createGraphWithNoArgs()
      val altContributedInterface = altGraph.callProperty<Any>("contributedInterface")
      assertThat(altContributedInterface).isNotNull()
      assertThat(altContributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes for a different type in a merging module`() {
    compile(
      source(
        """
        abstract class AltScope private constructor()

        interface ContributedInterface
        interface OtherInterface

        @ContributesBinding(AppScope::class, binding = binding<ContributedInterface>())
        @ContributesBinding(AltScope::class, binding = binding<OtherInterface>())
        @Inject
        class Impl : ContributedInterface, OtherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val otherInterface: OtherInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val altGraph = classLoader.loadClass("test.AltGraph").generatedImpl().createGraphWithNoArgs()
      val altContributedInterface = altGraph.callProperty<Any>("otherInterface")
      assertThat(altContributedInterface).isNotNull()
      assertThat(altContributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes for a different type in a downstream module`() {
    val previousCompilation =
      compile(
        source(
          """
          abstract class AltScope private constructor()

          interface ContributedInterface
          interface OtherInterface

          @ContributesBinding(AppScope::class, binding = binding<ContributedInterface>())
          @ContributesBinding(AltScope::class, binding = binding<OtherInterface>())
          @Inject
          class Impl : ContributedInterface, OtherInterface
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val otherInterface: OtherInterface
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = previousCompilation,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val altGraph = classLoader.loadClass("test.AltGraph").generatedImpl().createGraphWithNoArgs()
      val altContributedInterface = altGraph.callProperty<Any>("otherInterface")
      assertThat(altContributedInterface).isNotNull()
      assertThat(altContributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes with a qualifier difference in a merging module`() {
    compile(
      source(
        """
        abstract class AltScope private constructor()

        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        @ContributesBinding(AltScope::class, binding = binding<@Named("Alt") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          @Named("Alt")
          val otherInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val altGraph = classLoader.loadClass("test.AltGraph").generatedImpl().createGraphWithNoArgs()
      val altContributedInterface = altGraph.callProperty<Any>("otherInterface")
      assertThat(altContributedInterface).isNotNull()
      assertThat(altContributedInterface.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding supports multiple scopes for a different type without leaking the bindings`() {
    compile(
      source(
        """
        abstract class AltScope private constructor()

        interface ContributedInterface
        interface OtherInterface

        @ContributesBinding(AppScope::class, binding = binding<ContributedInterface>())
        @ContributesBinding(AltScope::class, binding = binding<OtherInterface>())
        @Inject
        class Impl : ContributedInterface, OtherInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        @DependencyGraph(scope = AltScope::class)
        interface AltGraph {
          val contributedInterface: ContributedInterface
          val otherInterface: OtherInterface
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: AltScope.kt:24:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.ContributedInterface

            test.ContributedInterface is requested at
                [test.AltGraph] test.AltGraph.contributedInterface

        Similar bindings:
          - Impl (Subtype). Type: ConstructorInjected. Source: AltScope.kt:12:1
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - scope only - implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - scope only - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
        @ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - with qualifiers - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<@Named("1") ContributedInterface>())
        @ContributesIntoSet(AppScope::class, binding<@Named("1") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations with different qualifiers are ok - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<@Named("1") ContributedInterface>())
        @ContributesIntoSet(AppScope::class, binding<@Named("2") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Set<ContributedInterface>
          @Named("2") val contributedInterfaces2: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Set<Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.first().javaClass.name).isEqualTo("test.Impl")
      val contributedInterfaces2 = graph.callProperty<Set<Any>>("contributedInterfaces2")
      assertThat(contributedInterfaces2).isNotNull()
      assertThat(contributedInterfaces2).hasSize(1)
      assertThat(contributedInterfaces2.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations with different qualifiers are ok - mixed`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        @ContributesIntoSet(AppScope::class, binding<@Named("2") ContributedInterface>())
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Set<ContributedInterface>
          @Named("2") val contributedInterfaces2: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Set<Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.first().javaClass.name).isEqualTo("test.Impl")
      val contributedInterfaces2 = graph.callProperty<Set<Any>>("contributedInterfaces2")
      assertThat(contributedInterfaces2).isNotNull()
      assertThat(contributedInterfaces2).hasSize(1)
      assertThat(contributedInterfaces2.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `implicit bound types use class qualifier - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Set<Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoSet supports explicit bound type with class-level qualifier`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Set<Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces.size).isEqualTo(1)
      assertThat(contributedInterfaces.single().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - scope only - mix of explicit and implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
        @ContributesIntoSet(AppScope::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `binding as Nothing is an error - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<Nothing>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Explicit bound types should not be `Nothing` or `Nothing?`."
      )
    }
  }

  @Test
  fun `binding can be Any - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<Any>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding is not assignable - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<Unit>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Class test.Impl does not implement explicit bound type kotlin.Unit"
      )
    }
  }

  @Test
  fun `binding can be ancestor - ContributesIntoSet`() {
    compile(
      source(
        """
        interface BaseContributedInterface

        interface ContributedInterface : BaseContributedInterface

        @ContributesIntoSet(AppScope::class, binding<BaseContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val bases: Set<BaseContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val bases = graph.callProperty<Set<Any>>("bases")
      assertThat(bases).isNotNull()
      assertThat(bases).hasSize(1)
      assertThat(bases.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `binding class must be injected - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoSet` is only applicable to constructor-injected classes, assisted factories, or objects. Ensure test.Impl is injectable or a bindable object."
      )
    }
  }

  @Test
  fun `binding with no explicit bound type or supertypes is an error - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        @Inject
        class Impl

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoSet`-annotated class test.Impl has no supertypes to bind to."
      )
    }
  }

  @Test
  fun `binding assisted factory is ok - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @AssistedInject
        class Impl(@Assisted input: String) {
          @ContributesIntoSet(AppScope::class)
          @AssistedFactory
          fun interface Factory : ContributedInterface {
            fun create(input: String): Impl
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding must not be the same as the class - ContributesIntoSet`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class, binding<Impl>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  @Test
  fun `binding with no supertypes and not Any is an error - ContributesIntoSet`() {
    compile(
      source(
        """
        @ContributesIntoSet(AppScope::class, binding<Impl>())
        @Inject
        class Impl
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: Impl.kt:7:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations are an error - scope only - implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @ContributesIntoMap(AppScope::class)
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations are an error - scope only - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) ContributedInterface>())
        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) ContributedInterface>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations are an error - with qualifiers - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) @Named("1") ContributedInterface>())
        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) @Named("1") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `ContributesTo annotations on a class, abstract class and object type are an error`() {
    compile(
      source(
        """
        @ContributesTo(AppScope::class)
        interface ContributedInterface
        @ContributesTo(AppScope::class)
        class ContributedClass
        @ContributesTo(AppScope::class)
        abstract class ContributedAbstractClass
        @ContributesTo(AppScope::class)
        object ContributedObject
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 `@ContributesTo` annotations only permitted on interfaces. However ContributedClass is a CLASS.
        e: ContributedInterface.kt:11:1 `@ContributesTo` annotations only permitted on interfaces. However ContributedAbstractClass is a CLASS.
        e: ContributedInterface.kt:13:1 `@ContributesTo` annotations only permitted on interfaces. However ContributedObject is a OBJECT.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations with different qualifiers are ok - explicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) @Named("1") ContributedInterface>())
        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) @Named("2") ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
          @Named("2") val contributedInterfaces2: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces1.entries.first().value.javaClass.name).isEqualTo("test.Impl")
      val contributedInterfaces2 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces2")
      assertThat(contributedInterfaces2).isNotNull()
      assertThat(contributedInterfaces2).hasSize(1)
      assertThat(contributedInterfaces2.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces2.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations with different qualifiers are ok - mixed`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) @Named("2") ContributedInterface>())
        @Named("1")
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
          @Named("2") val contributedInterfaces2: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces1.entries.first().value.javaClass.name).isEqualTo("test.Impl")
      val contributedInterfaces2 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces2")
      assertThat(contributedInterfaces2).isNotNull()
      assertThat(contributedInterfaces2).hasSize(1)
      assertThat(contributedInterfaces2.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces2.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `implicit bound types use class qualifier - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @Named("1")
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces1.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `ContributesIntoMap supports explicit bound type with class-level qualifier`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) ContributedInterface>())
        @Named("1")
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Named("1") val contributedInterfaces1: Map<KClass<*>, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces.size).isEqualTo(1)
      assertThat(contributedInterfaces.entries.single().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `explicit bound types into map must declare map key`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<ContributedInterface>())
        @ClassKey(Impl::class) // Class key is ignored if bound is explicit
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `explicit bound types into map must declare map key - class is ok`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<ContributedInterface>())
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 `@ContributesIntoMap`-annotated class @test.Impl must declare a map key but doesn't. Add one on the explicit bound type or the class."
      )
    }
  }

  @Test
  fun `implicit bound types into map must declare map key on class`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoMap`-annotated class test.Impl must declare a map key on the class or an explicit bound type but doesn't."
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoMap annotations are an error - scope only - mix of explicit and implicit`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) ContributedInterface>())
        @ContributesIntoMap(AppScope::class)
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoMap` annotations contributing to scope `AppScope`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `binding as Nothing is an error - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<Nothing>())
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Explicit bound types should not be `Nothing` or `Nothing?`."
      )
    }
  }

  @Test
  fun `binding can be Any - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) Any>())
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding is not assignable - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<Unit>())
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Class test.Impl does not implement explicit bound type kotlin.Unit"
      )
    }
  }

  @Test
  fun `binding can be ancestor - ContributesIntoMap`() {
    compile(
      source(
        """
        interface BaseContributedInterface

        interface ContributedInterface : BaseContributedInterface

        @ContributesIntoMap(AppScope::class, binding<@ClassKey(Impl::class) BaseContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val bases: Map<KClass<*>, BaseContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val bases = graph.callProperty<Map<KClass<*>, Any>>("bases")
      assertThat(bases).isNotNull()
      assertThat(bases).hasSize(1)
      assertThat(bases.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(bases.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `binding class must be injected - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoMap` is only applicable to constructor-injected classes, assisted factories, or objects. Ensure test.Impl is injectable or a bindable object."
      )
    }
  }

  @Test
  fun `binding with no explicit bound type or supertypes is an error - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @Inject
        class Impl

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoMap`-annotated class test.Impl has no supertypes to bind to."
      )
    }
  }

  @Test
  fun `binding assisted factory is ok - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @AssistedInject
        class Impl(@Assisted input: String) {
          @StringKey("Key")
          @ContributesIntoMap(AppScope::class)
          @AssistedFactory
          fun interface Factory : ContributedInterface {
            fun create(input: String): Impl
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: Map<String, ContributedInterface>
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binding must not be the same as the class - ContributesIntoMap`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class, binding<Impl>())
        @ClassKey(Impl::class)
        @Inject
        class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  @Test
  fun `binding with no supertypes and not Any is an error - ContributesIntoMap`() {
    compile(
      source(
        """
        @ContributesIntoMap(AppScope::class, binding<Impl>())
        @ClassKey(Impl::class)
        @Inject
        class Impl
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: Impl.kt:7:46 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl."
      )
    }
  }

  /**
   * This is a regression test to ensure that scope keys in the same package (i.e. no explicit
   * import) are resolvable. Essentially it ensures the supertype generation attempts to resolve the
   * scope key class in both regular resolution ("hey is this class resolved?") and using
   * `TypeResolverService` ("hey can you resolve this in the context of this class?").
   */
  @Test
  fun `scope keys in the same package work`() {
    compile(
      source(
        """
        abstract class UserScope private constructor()
        """
          .trimIndent()
      ),
      source(
        """
        @ContributesTo(UserScope::class)
        interface ContributedInterface

        @DependencyGraph(scope = UserScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface", scope = "UserScope")
    }
  }

  @Test
  fun `exclusions are respected - interface`() {
    compile(
      source(
        """
        @ContributesTo(AppScope::class)
        interface ContributedInterface

        @DependencyGraph(scope = AppScope::class, excludes = [ContributedInterface::class])
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph
      assertThat(graph.allSupertypes().map { it.name }).isEmpty()
    }
  }

  @Test
  fun `exclusions are respected - binding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        object Impl1 : ContributedInterface

        @ContributesBinding(AppScope::class)
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("contributedInterface"))
    }
  }

  @Test
  fun `exclusions are respected - into set`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        object Impl1 : ContributedInterface

        @ContributesIntoSet(AppScope::class)
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Set<*>>("contributedInterfaces")).hasSize(1)
    }
  }

  @Test
  fun `exclusions are respected - into map`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @StringKey("Impl1")
        object Impl1 : ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @StringKey("Impl2")
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
        interface ExampleGraph {
          val contributedInterfaces: Map<String, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Map<String, *>>("contributedInterfaces")).hasSize(1)
    }
  }

  @Ignore("TODO revisit when there's a better way to do this")
  @Test
  fun `unused exclusions are an error`() {
    compile(
      source(
        """
        interface ContributedInterface

        object Impl1 : ContributedInterface

        @DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
    ) {
      assertThat(messages)
        .contains(
          "Some excluded types were not matched. These can be removed from test.ExampleGraph: [test/Impl1]"
        )
    }
  }

  @Test
  fun `replacements are respected - interface`() {
    compile(
      source(
        """
        @ContributesTo(AppScope::class)
        interface ContributedInterface1

        @ContributesTo(AppScope::class, replaces = [ContributedInterface1::class])
        interface ContributedInterface2

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface2")
    }
  }

  @Test
  fun `replacements are respected - binding`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesBinding(AppScope::class)
        object Impl1 : ContributedInterface

        @ContributesBinding(AppScope::class, replaces = [Impl1::class])
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("contributedInterface"))
    }
  }

  @Test
  fun `replacements are respected - into set`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoSet(AppScope::class)
        object Impl1 : ContributedInterface

        @ContributesIntoSet(AppScope::class, replaces = [Impl1::class])
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Set<*>>("contributedInterfaces")).hasSize(1)
    }
  }

  @Test
  fun `replacements are respected - into map`() {
    compile(
      source(
        """
        interface ContributedInterface

        @ContributesIntoMap(AppScope::class)
        @StringKey("Impl1")
        object Impl1 : ContributedInterface

        @ContributesIntoMap(AppScope::class, replaces = [Impl1::class])
        @StringKey("Impl2")
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterfaces: Map<String, ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Map<String, *>>("contributedInterfaces")).hasSize(1)
    }
  }

  @Ignore("TODO revisit when there's a better way to do this")
  @Test
  fun `unused replacements are an error`() {
    compile(
      source(
        """
        interface ContributedInterface

        object Impl1 : ContributedInterface

        @ContributesBinding(AppScope::class, replaces = [Impl1::class])
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
    ) {
      assertThat(messages)
        .contains(
          "Some replaced types were not matched. These can be removed from test.ExampleGraph: [test/Impl1]"
        )
    }
  }

  @Test
  fun `scoped binding is still scoped`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Inject
        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class)
        class Impl1 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Any>("contributedInterface"))
        .isSameInstanceAs(graph.callProperty<Any>("contributedInterface"))
    }
  }

  @Test
  fun `replaced scoped binding is still scoped`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Inject
        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class)
        class Impl1 : ContributedInterface

        @Inject
        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class, replaces = [Impl1::class])
        class Impl2(
          val impl1: Impl1
        ) : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
          val impl1: Impl1
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val impl2 = graph.callProperty<Any>("contributedInterface")
      assertThat(impl2.javaClass.simpleName).isEqualTo("Impl2")
      assertThat(impl2).isSameInstanceAs(graph.callProperty<Any>("contributedInterface"))
      val impl1 = impl2.callProperty<Any>("impl1")
      assertThat(impl1).isSameInstanceAs(graph.callProperty<Any>("impl1"))
    }
  }

  @Test
  fun `B SingleIn is respected, when injected directly into A`() {
    compile(
      source(
        """
        @SingleIn(AppScope::class) @Inject class B
        @Inject class A(val b1: B, val b2: B) {
          fun areEqual(): Boolean = b1 == b2
        }
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val a: A
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(appGraph.callProperty<Any>("a").callFunction<Boolean>("areEqual")).isTrue()
    }
  }

  @Test
  fun `B SingleIn is respected, when injected into AImpl and binding interface is used`() {
    compile(
      source(
        """
        @SingleIn(AppScope::class) @Inject class B
        interface A
        @ContributesBinding(AppScope::class)
        @Inject class AImpl(val b1: B, val b2: B): A {
          fun areEqual(): Boolean = b1 == b2
        }
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val a: A
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(appGraph.callProperty<Any>("a").callFunction<Boolean>("areEqual")).isTrue()
    }
  }

  @Test
  fun `B SingleIn is respected, when injected into a wrapper class`() {
    compile(
      source(
        """
        @SingleIn(AppScope::class) @Inject class B
        @Inject class BWrapper(val b1: B, val b2: B)
        @Inject class A(val bWrapper: BWrapper) {
          fun areEqual(): Boolean = bWrapper.b1 == bWrapper.b2
        }
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val a: A
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(appGraph.callProperty<Any>("a").callFunction<Boolean>("areEqual")).isTrue()
    }
  }

  @Test
  fun `binding scope is respected regardless of where it is injected`() {
    compile(
      source(
        """
        @SingleIn(AppScope::class) @Inject class B
        @Inject class BWrapper(val b1: B, val b2: B)
        interface A
        @ContributesBinding(AppScope::class)
        @Inject class AImpl(val bWrapper: BWrapper) : A {
          fun areEqual(): Boolean = bWrapper.b1 == bWrapper.b2
        }
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val a: A
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(appGraph.callProperty<Any>("a").callFunction<Boolean>("areEqual")).isTrue()
    }
  }

  @Test
  fun `B SingleIn is respected, when binding interface starts with a letter AFTER B and injected into a wrapper class`() {
    compile(
      source(
        """
        @SingleIn(AppScope::class) @Inject class B
        @Inject class BWrapper(val b1: B, val b2: B)
        interface C
        @ContributesBinding(AppScope::class)
        @Inject class CImpl(val bWrapper: BWrapper) : C {
          fun areEqual(): Boolean = bWrapper.b1 == bWrapper.b2
        }
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val a: C
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(appGraph.callProperty<Any>("a").callFunction<Boolean>("areEqual")).isTrue()
    }
  }

  @Test
  fun `SingleIn is respected when combining Provider and multibindings`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Inject
        @ContributesIntoSet(AppScope::class)
        class Impl(val singleton: Singleton) : ContributedInterface

        @Inject @SingleIn(AppScope::class) class Singleton

        @Inject class Wrapper(val provider: Provider<Set<ContributedInterface>>)

        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val wrapper: Wrapper
          val singleton: Singleton
        }
        """
          .trimIndent()
      )
    ) {
      val appGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val singleton0 = appGraph.callProperty<Any>("singleton")
      val provider = appGraph.callProperty<Any>("wrapper").callProperty<Any>("provider")
      val singleton1 =
        provider.callFunction<Set<Any>>("invoke").first().callProperty<Any>("singleton")
      val singleton2 =
        provider.callFunction<Set<Any>>("invoke").first().callProperty<Any>("singleton")
      assertThat(singleton0).isSameInstanceAs(singleton1)
      assertThat(singleton0).isSameInstanceAs(singleton2)
    }
  }
}
