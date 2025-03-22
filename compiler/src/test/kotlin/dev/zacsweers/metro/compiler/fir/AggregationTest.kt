// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.allSupertypes
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.kotlin.name.ClassId
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
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly(
          "test.ContributedInterface$$\$MetroContribution",
          "test.ContributedInterface",
        )
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
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly(
          "test.ContributedInterface$$\$MetroContribution",
          "test.ContributedInterface",
        )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterfaces = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces")
      assertThat(contributedInterfaces).isNotNull()
      assertThat(contributedInterfaces).hasSize(1)
      assertThat(contributedInterfaces.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces.entries.first().value.javaClass.name).isEqualTo("test.Impl")
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
      val contributedInterface2 = graph.callProperty<Any>("contributedInterface2")
      assertThat(contributedInterface2).isNotNull()
      assertThat(contributedInterface2.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `repeated ContributesBinding annotations with different scopes and same bound types are ok2`() {
    compile(
      source(
        packageName = "com.squareup.anvil.annotations",
        source =
          """
            annotation class ContributesBinding(
              val scope: KClass<*>,
              val boundType: KClass<*> = Unit::class,
            )
            """
            .trimIndent(),
      ),
      source(
        """
          import com.squareup.anvil.annotations.ContributesBinding
          import dev.zacsweers.metro.Inject

          interface ContributedInterface
          interface SecondInterface

          @ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          @Inject
          class Impl : ContributedInterface, SecondInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options =
        metroOptions.copy(
          customContributesBindingAnnotations =
            setOf(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding"))
        ),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
    }
  }

  // TODO loosen this restriction
  @Test
  fun `explicit bound types don't use class qualifier - ContributesBinding`() {
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
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:16:3 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("1") test.ContributedInterface

              @Named("1") test.ContributedInterface is requested at
                  [test.ExampleGraph] test.ExampleGraph.contributedInterface1
        """
          .trimIndent()
      )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
  fun `binding class must be not be assisted injected - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface

          @ContributesBinding(AppScope::class)
          @Inject
          class Impl(@Assisted input: String) : ContributedInterface {
            @AssistedFactory
            fun interface Factory {
              fun create(input: String): Impl
            }
          }

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesBinding` doesn't make sense on assisted-injected class test.Impl. Did you mean to apply this to its assisted factory?"
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

          @Inject
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Set<Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.first().javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `explicit bound types don't use class qualifier - ContributesIntoSet`() {
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
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:16:3 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("1") kotlin.collections.Set<test.ContributedInterface>

              @Named("1") kotlin.collections.Set<test.ContributedInterface> is requested at
                  [test.ExampleGraph] test.ExampleGraph.contributedInterfaces1
        """
          .trimIndent()
      )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
  fun `binding class must be not be assisted injected - ContributesIntoSet`() {
    compile(
      source(
        """
          interface ContributedInterface

          @ContributesIntoSet(AppScope::class)
          @Inject
          class Impl(@Assisted input: String) : ContributedInterface {
            @AssistedFactory
            fun interface Factory {
              fun create(input: String): Impl
            }
          }

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoSet` doesn't make sense on assisted-injected class test.Impl. Did you mean to apply this to its assisted factory?"
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

          @Inject
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterfaces1 = graph.callProperty<Map<KClass<*>, Any>>("contributedInterfaces1")
      assertThat(contributedInterfaces1).isNotNull()
      assertThat(contributedInterfaces1).hasSize(1)
      assertThat(contributedInterfaces1.entries.first().key.java.name).isEqualTo("test.Impl")
      assertThat(contributedInterfaces1.entries.first().value.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `explicit bound types don't use class qualifier - ContributesIntoMap`() {
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
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:16:3 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("1") kotlin.collections.Map<kotlin.reflect.KClass<*>, test.ContributedInterface>

              @Named("1") kotlin.collections.Map<kotlin.reflect.KClass<*>, test.ContributedInterface> is requested at
                  [test.ExampleGraph] test.ExampleGraph.contributedInterfaces1
        """
          .trimIndent()
      )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
  fun `binding class must be not be assisted injected - ContributesIntoMap`() {
    compile(
      source(
        """
          interface ContributedInterface

          @ContributesIntoMap(AppScope::class)
          @Inject
          class Impl(@Assisted input: String) : ContributedInterface {
            @AssistedFactory
            fun interface Factory {
              fun create(input: String): Impl
            }
          }

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ContributedInterface.kt:9:1 `@ContributesIntoMap` doesn't make sense on assisted-injected class test.Impl. Did you mean to apply this to its assisted factory?"
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

          @Inject
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
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly(
          "test.ContributedInterface$$\$MetroContribution",
          "test.ContributedInterface",
        )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly(
          "test.ContributedInterface2",
          "test.ContributedInterface2$$\$MetroContribution",
        )
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val impl2 = graph.callProperty<Any>("contributedInterface")
      assertThat(impl2.javaClass.simpleName).isEqualTo("Impl2")
      assertThat(impl2).isSameInstanceAs(graph.callProperty<Any>("contributedInterface"))
      val impl1 = impl2.callProperty<Any>("impl1")
      assertThat(impl1).isSameInstanceAs(graph.callProperty<Any>("impl1"))
    }
  }
}
