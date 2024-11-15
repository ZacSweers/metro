/*
 * Copyright (C) 2024 Zac Sweers
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
package dev.zacsweers.lattice.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleComponent
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.callComponentAccessor
import dev.zacsweers.lattice.compiler.callComponentAccessorProperty
import dev.zacsweers.lattice.compiler.createComponentViaFactory
import dev.zacsweers.lattice.compiler.generatedLatticeComponent
import java.util.concurrent.Callable
import org.junit.Test

class ComponentTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.createComponentFactory
            import java.util.concurrent.Callable

            @Singleton
            @Component
            abstract class ExampleComponent(
              @get:Provides
              val text: String
            ) {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(text: String): ExampleComponent
              }
            }

            @Singleton
            @Inject
            class ExampleClass(private val text: String) : Callable<String> {
              override fun call(): String = text
            }

            fun createExampleClass(): (String) -> Callable<String> {
              val factory = createComponentFactory<ExampleComponent.Factory>()
              return { factory.create(it).exampleClass() }
            }

          """
            .trimIndent(),
        )
      )
    val component =
      result.ExampleComponent.generatedLatticeComponent().createComponentViaFactory("Hello, world!")

    val exampleClass = component.callComponentAccessor<Callable<String>>("exampleClass")
    assertThat(exampleClass.call()).isEqualTo("Hello, world!")

    // 2nd pass exercising creating a component via createComponentFactory()
    @Suppress("UNCHECKED_CAST")
    val callableCreator =
      result.classLoader
        .loadClass("test.ExampleComponentKt")
        .getDeclaredMethod("createExampleClass")
        .invoke(null) as (String) -> Callable<String>
    val callable = callableCreator("Hello, world!")
    assertThat(callable.call()).isEqualTo("Hello, world!")
  }

  @Test
  fun `missing binding should fail compilation and report property accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:10:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @Named("hello")
              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with get site target qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @get:Named("hello")
              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              fun text(): String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:10:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @Named("hello")
              fun text(): String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject

            @Component
            abstract class ExampleComponent() {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:19:20 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.ExampleComponent] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleComponent] test.ExampleComponent.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named

            @Component
            abstract class ExampleComponent() {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(@Named("hello") private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:20:20 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is injected at
                [test.ExampleComponent] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleComponent] test.ExampleComponent.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `scoped bindings are scoped correctly`() {
    // Ensure scoped bindings are properly scoped
    // This means that any calls to them should return the same instance, while any calls
    // to unscoped bindings are called every time.
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Component
            abstract class ExampleComponent {
            
              private var scopedCounter = 0
              private var unscopedCounter = 0

              @Named("scoped") 
              abstract val scoped: String

              @Named("unscoped") 
              abstract val unscoped: String

              @Singleton
              @Provides
              @Named("scoped") 
              fun provideScoped(): String = "text " + scopedCounter++

              @Provides
              @Named("unscoped") 
              fun provideUnscoped(): String = "text " + unscopedCounter++

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(@Named("hello") private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
        debug = true,
      )

    val component = result.ExampleComponent.generatedLatticeComponent()

    // Repeated calls to the scoped instance only every return one value
    assertThat(component.callComponentAccessorProperty<String>("scoped")).isEqualTo("text 0")
    assertThat(component.callComponentAccessorProperty<String>("scoped")).isEqualTo("text 0")

    // Repeated calls to the unscoped instance recompute each time
    assertThat(component.callComponentAccessorProperty<String>("unscoped")).isEqualTo("text 0")
    assertThat(component.callComponentAccessorProperty<String>("unscoped")).isEqualTo("text 1")
  }

  // TODO
  //  - scoping
  //  - advanced graph resolution (i.e. complex dep chains)
  //  - break-the-chain deps
  //  - provides
  //  - @get:Provides
  //  - Binds examples
  //  - Component deps
  //  - Supertype providers
}
