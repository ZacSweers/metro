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
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleComponent
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.componentFactoryClass
import dev.zacsweers.lattice.compiler.createComponentViaFactory
import dev.zacsweers.lattice.compiler.generatedLatticeComponent
import dev.zacsweers.lattice.compiler.provideValueAs
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
        ),
        debug = true,
      )

    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
    val component = result.ExampleComponent.generatedLatticeComponent().createComponentViaFactory()
    val providedValue =
      result.ExampleComponent.componentFactoryClass()
        .provideValueAs<String>("provideValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // TODO test newInstance call + invoke
  }
}
