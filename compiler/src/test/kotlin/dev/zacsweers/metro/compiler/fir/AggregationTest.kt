// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.allSupertypes
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import kotlin.test.Test
import org.junit.Ignore

class AggregationTest : MetroCompilerTest() {

  override val extraImports: List<String> = listOf("kotlin.reflect.*")

  private val usesDirectBindingDeclarations: Boolean
    get() =
      metroOptions.omitRedundantMirrors &&
        CompatContext.create(CompatContext.Factory.loadCompilerVersion())
          .supportsAnnotationArgumentInvalidation

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
            .trimIndent(),
        ),
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
          .trimIndent(),
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
          .trimIndent(),
      ),
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
          .trimIndent(),
      ),
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
          .trimIndent(),
      ),
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
            .trimIndent(),
        ),
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
          .trimIndent(),
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
          .trimIndent(),
      ),
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
            .trimIndent(),
        ),
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
          .trimIndent(),
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
          .trimIndent(),
      ),
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
          .trimIndent(),
      ),
      source(
        """
        @ContributesTo(UserScope::class)
        interface ContributedInterface

        @DependencyGraph(scope = UserScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface", scope = "UserScope")
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
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
    ) {
      assertThat(messages)
        .contains(
          "Some excluded types were not matched. These can be removed from test.ExampleGraph: [test/Impl1]",
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
          .trimIndent(),
      ),
    ) {
      val graph = ExampleGraph
      graph.assertHasContributedSupertype("test.ContributedInterface2")
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
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.INTERNAL_ERROR,
    ) {
      assertThat(messages)
        .contains(
          "Some replaced types were not matched. These can be removed from test.ExampleGraph: [test/Impl1]",
        )
    }
  }

  /**
   * This test verifies that when an `@IntoMap` binding has a `@MapKey` annotation that is not
   * visible to the consuming graph's compilation (because the annotation class is in a transitive
   * dependency that isn't on the direct classpath), we report a useful error message.
   *
   * Structure:
   * - Module "common": Defines `@MapKey annotation class ServiceKey` (internal visibility)
   * - Module "feature": Depends on common, defines `TestClass` with `@ServiceKey` and
   *   `@ContributesIntoMap`
   * - Module "main": Depends only on "feature" (not common directly), defines `AppGraph`
   *
   * When "main" compiles, it can see `TestClass` (from feature), but not the `@MapKey` annotation
   * on `ServiceKey` (from common), causing the error.
   *
   * https://github.com/ZacSweers/metro/issues/1509
   */
  @Test
  fun `ContributesIntoMap with transitive invisible map key has a useful error`() {
    // Module "common" - defines the MapKey annotation
    val commonCompilation =
      compile(
        source(
          fileNameWithoutExtension = "ServiceKey",
          source =
            """
            import dev.zacsweers.metro.MapKey
            import java.io.Closeable

            @MapKey
            annotation class ServiceKey(val value: KClass<out Closeable>)
            """
              .trimIndent(),
          packageName = "common",
        ),
      )

    // Module "feature" - depends on common, defines TestClass with the ServiceKey
    val featureCompilation =
      compile(
        source(
          fileNameWithoutExtension = "TestClass",
          source =
            """
            import common.ServiceKey
            import dev.zacsweers.metro.ContributesIntoMap
            import dev.zacsweers.metro.Inject
            import java.io.Closeable

            @Inject
            @ContributesIntoMap(AppScope::class)
            @ServiceKey(TestClass::class)
            class TestClass : Closeable {
              override fun close() {}
            }
            """
              .trimIndent(),
          packageName = "feature",
        ),
        compilationBlock = { addPreviousResultToClasspath(commonCompilation) },
      )
    val expectedDeclarationContext =
      if (usesDirectBindingDeclarations) {
        """
        Encountered while processing declaration 'feature.TestClass.MetroContributionToAppScope.bindIntoMapAsCloseable419268483' (no source location available)
        - This is Metro-generated code that contributes 'feature.TestClass' (where the problem is) to AppScope.
        """
          .trimIndent()
      } else {
        val bindsMirrorFunctionName =
          featureCompilation.classLoader
            .loadClass("feature.TestClass\$MetroContributionToAppScope\$BindsMirror")
            .declaredMethods
            .single()
            .name
        """
        Encountered while processing declaration 'feature.TestClass.MetroContributionToAppScope.BindsMirror.$bindsMirrorFunctionName' (no source location available)
        - This is Metro-generated code that contributes 'feature.TestClass' (where the problem is) to AppScope.
        """
          .trimIndent()
      }

    // Module "main" - depends only on feature (not common), defines the graph
    // This should fail because ServiceKey is not visible to this compilation
    compile(
      source(
        """
        import feature.TestClass
        import java.io.Closeable

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Multibinds
          fun services(): Map<KClass<out Closeable>, Closeable>
        }
        """
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
      compilationBlock = {
        // Only add feature, not common - this simulates the transitive dependency scenario
        addPreviousResultToClasspath(featureCompilation)
      },
    ) {
      assertDiagnostics(
        buildString {
          appendLine(
            "e: Found an @IntoMap annotation without any @MapKey annotations. This may happen if this is an external declaration that has a map key annotation that is not visible to this compilation. Please check the original source.",
          )
          appendLine()
          appendLine("(context)")
          append(expectedDeclarationContext)
        },
      )
    }
  }

  @Test
  fun `ContributesIntoMap explicit binds with transitive invisible map key has a useful error`() {
    val commonCompilation =
      compile(
        source(
          fileNameWithoutExtension = "ServiceKey",
          source =
            """
            import dev.zacsweers.metro.MapKey
            import java.io.Closeable

            @MapKey
            annotation class ServiceKey(val value: KClass<out Closeable>)
            """
              .trimIndent(),
          packageName = "common",
        ),
      )

    // Module "feature" - depends on common, defines TestClass with the ServiceKey
    val featureCompilation =
      compile(
        source(
          fileNameWithoutExtension = "TestClass",
          source =
            """
            import common.ServiceKey
            import dev.zacsweers.metro.ContributesIntoMap
            import dev.zacsweers.metro.Inject
            import dev.zacsweers.metro.IntoMap
            import java.io.Closeable

            @Inject
            class TestClass : Closeable {
              override fun close() {}
            }

            @ContributesTo(AppScope::class)
            interface Bindings {
              @Binds
              @IntoMap
              @ServiceKey(TestClass::class)
              val TestClass.bind: Closeable
            }
            """
              .trimIndent(),
          packageName = "feature",
        ),
        compilationBlock = { addPreviousResultToClasspath(commonCompilation) },
      )
    val expectedDeclarationContext =
      if (usesDirectBindingDeclarations) {
        "Encountered while processing declaration 'feature.Bindings.bind' (no source location available)"
      } else {
        val bindsMirrorFunctionName =
          featureCompilation.classLoader
            .loadClass("feature.Bindings\$BindsMirror")
            .declaredMethods
            .single()
            .name
        """
        Encountered while processing declaration 'feature.Bindings.BindsMirror.$bindsMirrorFunctionName' (no source location available)
        - This is Metro-generated code for 'feature.Bindings.bind' (where the problem is).
        """
          .trimIndent()
      }

    // Module "main" - depends only on feature (not common), defines the graph
    // This should fail because ServiceKey is not visible to this compilation
    compile(
      source(
        """
        import feature.TestClass
        import java.io.Closeable

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Multibinds
          fun services(): Map<KClass<out Closeable>, Closeable>
        }
        """
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
      compilationBlock = {
        // Only add feature, not common - this simulates the transitive dependency scenario
        addPreviousResultToClasspath(featureCompilation)
      },
    ) {
      assertDiagnostics(
        buildString {
          appendLine(
            "e: Found an @IntoMap annotation without any @MapKey annotations. This may happen if this is an external declaration that has a map key annotation that is not visible to this compilation. Please check the original source.",
          )
          appendLine()
          appendLine("(context)")
          append(expectedDeclarationContext)
        },
      )
    }
  }

  @Test
  fun `ContributesIntoMap explicit provides with transitive invisible map key has a useful error`() {
    val commonCompilation =
      compile(
        source(
          fileNameWithoutExtension = "ServiceKey",
          source =
            """
            import dev.zacsweers.metro.MapKey
            import java.io.Closeable

            @MapKey
            annotation class ServiceKey(val value: KClass<out Closeable>)
            """
              .trimIndent(),
          packageName = "common",
        ),
      )

    // Module "feature" - depends on common, defines TestClass with the ServiceKey
    val featureCompilation =
      compile(
        source(
          fileNameWithoutExtension = "TestClass",
          source =
            """
            import common.ServiceKey
            import dev.zacsweers.metro.ContributesIntoMap
            import dev.zacsweers.metro.Provides
            import dev.zacsweers.metro.IntoMap
            import java.io.Closeable

            class TestClass : Closeable {
              override fun close() {}
            }

            @ContributesTo(AppScope::class)
            interface Bindings {
              @Provides
              @IntoMap
              @ServiceKey(TestClass::class)
              fun provideCloseable(): Closeable = TestClass()
            }
            """
              .trimIndent(),
          packageName = "feature",
        ),
        compilationBlock = { addPreviousResultToClasspath(commonCompilation) },
      )

    // Module "main" - depends only on feature (not common), defines the graph
    // This should fail because ServiceKey is not visible to this compilation
    compile(
      source(
        """
        import feature.TestClass
        import java.io.Closeable

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Multibinds
          fun services(): Map<KClass<out Closeable>, Closeable>
        }
        """
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
      compilationBlock = {
        // Only add feature, not common - this simulates the transitive dependency scenario
        addPreviousResultToClasspath(featureCompilation)
      },
    ) {
      assertDiagnostics(
        """
        e: Found an @IntoMap annotation without any @MapKey annotations. This may happen if this is an external declaration that has a map key annotation that is not visible to this compilation. Please check the original source.

        (context)
        Encountered while processing declaration 'feature.Bindings.ProvideCloseableMetroFactory.declarationMirror' (no source location available)
        - This is Metro-generated code for 'feature.Bindings.provideCloseable(...)' (where the problem is).
        """
          .trimIndent(),
      )
    }
  }
}
