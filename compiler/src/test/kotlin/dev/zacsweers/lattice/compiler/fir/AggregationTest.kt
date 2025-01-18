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
import dev.zacsweers.lattice.compiler.ExampleGraph
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.allSupertypes
import dev.zacsweers.lattice.compiler.callProperty
import dev.zacsweers.lattice.compiler.createGraphWithNoArgs
import dev.zacsweers.lattice.compiler.generatedLatticeGraphClass
import kotlin.reflect.KClass
import kotlin.test.Test

// Need to resume these tests after fixing FIR generation bits first!
class AggregationTest : LatticeCompilerTest() {
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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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
          import kotlin.reflect.KClass

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

  // TODO
  //  FIR check for single bound type
  //  FIR duplicate contributes
  //  FIR redundant explicit bound type contributes
  //  FIR explicit bound type to Nothing
  //  FIR validate boundType is assignable
  //  FIR validate map key with intomap (class or bound type)
  //  doc behavior if class key/qualifier and BoundType annotation
}
