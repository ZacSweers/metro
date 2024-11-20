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
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleComponent
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.createComponentViaFactory
import dev.zacsweers.lattice.compiler.generatedLatticeComponentClass
import dev.zacsweers.lattice.compiler.invokeCreateAs
import dev.zacsweers.lattice.compiler.provideValueAs
import dev.zacsweers.lattice.compiler.providesFactoryClass
import dev.zacsweers.lattice.internal.Factory
import dev.zacsweers.lattice.provider
import org.junit.Test

class ProvidesTransformerTest : LatticeCompilerTest() {

  @Test
  fun `simple function provider`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideValue(): String = "Hello, world!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              val value: String get() = "Hello, world!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider in a companion object`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              companion object {
                @Provides
                fun provideValue(): String = "Hello, world!"
              }

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val providesFactoryClass = result.ExampleComponent.providesFactoryClass(companion = true)
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider in a companion object`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              companion object {
                @Provides
                val value: String get() = "Hello, world!"
              }

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val providesFactoryClass = result.ExampleComponent.providesFactoryClass(companion = true)
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider with arguments`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int): String = "Hello, ${'$'}intValue!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2)
    assertThat(providedValue).isEqualTo("Hello, 2!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component, provider { 2 })
    assertThat(providesFactory()).isEqualTo("Hello, 2!")
  }

  @Test
  fun `simple function provider with multiple arguments`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideBooleanValue(): Boolean = false
              
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int, booleanValue: Boolean): String = "Hello, ${'$'}intValue! ${'$'}booleanValue"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, true)
    assertThat(providedValue).isEqualTo("Hello, 2! true")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component, provider { 2 }, provider { true })
    assertThat(providesFactory()).isEqualTo("Hello, 2! true")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type key`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Named

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    // This is a tricky bit. This isn't how it would _actually_ work
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component, provider { 2 }, provider { 3 })
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type with different qualifiers`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Named

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1
              
              @Named("int2")
              @Provides
              fun provideIntValue2(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                @Named("int2") intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component, provider { 2 }, provider { 3 })
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }
}
