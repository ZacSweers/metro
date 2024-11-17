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
import org.junit.Test

class ProvidesTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
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
    // Exercise calling the static provideValue function directly
    val providedValue =
      result.ExampleComponent.providesFactoryClass()
        .provideValueAs<String>("provideValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      result.ExampleComponent.providesFactoryClass().invokeCreateAs<Factory<String>>(component)
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
    // Exercise calling the static provideValue function directly
    val providedValue =
      result.ExampleComponent.providesFactoryClass().provideValueAs<String>("getValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      result.ExampleComponent.providesFactoryClass().invokeCreateAs<Factory<String>>(component)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }
}
