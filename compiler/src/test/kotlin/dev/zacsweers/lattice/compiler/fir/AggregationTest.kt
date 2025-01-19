/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.lattice.compiler.ExampleGraph
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.allSupertypes
import dev.zacsweers.lattice.compiler.assertDiagnostics
import dev.zacsweers.lattice.compiler.callProperty
import dev.zacsweers.lattice.compiler.createGraphWithNoArgs
import dev.zacsweers.lattice.compiler.generatedLatticeGraphClass
import kotlin.reflect.KClass
import kotlin.test.Test

// Need to resume these tests after fixing FIR generation bits first!
class AggregationTest : LatticeCompilerTest() {

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
        .containsExactly("lattice.hints.TestContributedInterface", "test.ContributedInterface")
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
        .containsExactly("lattice.hints.TestContributedInterface", "test.ContributedInterface")
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<ContributedInterface>()
          )
          @ContributesBinding(
            AppScope::class,
            boundType = BoundType<AnotherInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@Named("hello") ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<ContributedInterface<String>>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@Named("named") ContributedInterface<String>>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@Named("hello") ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<ContributedInterface<String>>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@Named("named") ContributedInterface<String>>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@ClassKey(Impl::class) ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@ClassKey(Impl::class) @Named("hello") ContributedInterface>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@ClassKey(Impl::class) ContributedInterface<String>>()
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
            boundType = BoundType<@ClassKey(Impl::class) @Named("named") ContributedInterface<String>>()
          )
          @Inject
          class Impl : ContributedInterface<String>
        """
            .trimIndent()
        ),
        debug = true,
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
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:7:1 Duplicate `@ContributesTo` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:8:1 Duplicate `@ContributesTo` annotations contributing to scope `AppScope`.
        """.trimIndent()
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - scope only - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<ContributedInterface>())
          @ContributesBinding(AppScope::class, boundType = BoundType<ContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - with qualifiers - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @ContributesBinding(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations with different qualifiers are ok - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @ContributesBinding(AppScope::class, boundType = BoundType<@Named("2") ContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            @Named("1") val contributedInterface1: ContributedInterface
            @Named("2") val contributedInterface2: ContributedInterface
          }
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
          @ContributesBinding(AppScope::class, boundType = BoundType<@Named("2") ContributedInterface>())
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
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
      val contributedInterface2 = graph.callProperty<Any>("contributedInterface2")
      assertThat(contributedInterface2).isNotNull()
      assertThat(contributedInterface2.javaClass.name).isEqualTo("test.Impl")
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
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
      val contributedInterface1 = graph.callProperty<Any>("contributedInterface1")
      assertThat(contributedInterface1).isNotNull()
      assertThat(contributedInterface1.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `explicit bound types don't use class qualifier - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<ContributedInterface>())
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:16:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("1") test.ContributedInterface
          
              @Named("1") test.ContributedInterface is requested at
                  [test.ExampleGraph] test.ExampleGraph.contributedInterface1
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesBinding annotations are an error - scope only - mix of explicit and implicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<ContributedInterface>())
          @ContributesBinding(AppScope::class)
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesBinding` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `boundType as Nothing is an error - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<Nothing>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Explicit bound types should not be `Nothing` or `Nothing?`.")
    }
  }

  @Test
  fun `boundType can be Any - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<Any>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
    )
  }

  @Test
  fun `boundType is not assignable - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<Unit>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Class dev.zacsweers.lattice.ContributesBinding does not implement explicit bound type kotlin.Unit")
    }
  }

  @Test
  fun `boundType can be ancestor - ContributesBinding`() {
    compile(
      source(
        """
          interface BaseContributedInterface

          interface ContributedInterface : BaseContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<BaseContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val base: BaseContributedInterface
          }
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:1 `@ContributesBinding` is only applicable to constructor-injected classes. Did you forget to inject test.Impl?")
    }
  }

  @Test
  fun `binding must not be the same as the class - ContributesBinding`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesBinding(AppScope::class, boundType = BoundType<Impl>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl.")
    }
  }

  @Test
  fun `binding with no supertypes and not Any is an error - ContributesBinding`() {
    compile(
      source(
        """
          @ContributesBinding(AppScope::class, boundType = BoundType<Impl>())
          @Inject
          class Impl

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: Impl.kt:7:60 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl.")
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - scope only - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<ContributedInterface>())
          @ContributesIntoSet(AppScope::class, boundType = BoundType<ContributedInterface>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - with qualifiers - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @ContributesIntoSet(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations with different qualifiers are ok - explicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<@Named("1") ContributedInterface>())
          @ContributesIntoSet(AppScope::class, boundType = BoundType<@Named("2") ContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            @Named("1") val contributedInterfaces1: Set<ContributedInterface>
            @Named("2") val contributedInterfaces2: Set<ContributedInterface>
          }
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
          @ContributesIntoSet(AppScope::class, boundType = BoundType<@Named("2") ContributedInterface>())
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
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<ContributedInterface>())
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:16:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("1") kotlin.collections.Set<test.ContributedInterface>
          
              @Named("1") kotlin.collections.Set<test.ContributedInterface> is requested at
                  [test.ExampleGraph] test.ExampleGraph.contributedInterfaces1
        """.trimIndent()
      )
    }
  }

  @Test
  fun `duplicate ContributesIntoSet annotations are an error - scope only - mix of explicit and implicit`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<ContributedInterface>())
          @ContributesIntoSet(AppScope::class)
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:9:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
          e: ContributedInterface.kt:10:1 Duplicate `@ContributesIntoSet` annotations contributing to scope `AppScope`.
        """.trimIndent()
      )
    }
  }

  @Test
  fun `boundType as Nothing is an error - ContributesIntoSet`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<Nothing>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Explicit bound types should not be `Nothing` or `Nothing?`.")
    }
  }

  @Test
  fun `boundType can be Any - ContributesIntoSet`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<Any>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
    )
  }

  @Test
  fun `boundType is not assignable - ContributesIntoSet`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<Unit>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Class dev.zacsweers.lattice.ContributesIntoSet does not implement explicit bound type kotlin.Unit")
    }
  }

  @Test
  fun `boundType can be ancestor - ContributesIntoSet`() {
    compile(
      source(
        """
          interface BaseContributedInterface

          interface ContributedInterface : BaseContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<BaseContributedInterface>())
          @Inject
          class Impl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val bases: Set<BaseContributedInterface>
          }
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:1 `@ContributesIntoSet` is only applicable to constructor-injected classes. Did you forget to inject test.Impl?")
    }
  }

  @Test
  fun `binding must not be the same as the class - ContributesIntoSet`() {
    compile(
      source(
        """
          interface ContributedInterface
          
          @ContributesIntoSet(AppScope::class, boundType = BoundType<Impl>())
          @Inject
          class Impl : ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: ContributedInterface.kt:9:60 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl.")
    }
  }

  @Test
  fun `binding with no supertypes and not Any is an error - ContributesIntoSet`() {
    compile(
      source(
        """
          @ContributesIntoSet(AppScope::class, boundType = BoundType<Impl>())
          @Inject
          class Impl
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR
    ) {
      assertDiagnostics("e: Impl.kt:7:60 Redundant explicit bound type test.Impl is the same as the annotated class test.Impl.")
    }
  }
}
