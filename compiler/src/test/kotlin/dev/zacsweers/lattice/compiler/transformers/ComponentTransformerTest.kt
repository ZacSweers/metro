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
import dev.zacsweers.lattice.compiler.callComponentAccessor
import dev.zacsweers.lattice.compiler.generatedLatticeComponent
import dev.zacsweers.lattice.compiler.createComponentViaFactory
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

          """
            .trimIndent(),
        ),
        debug = true,
      )
    val component =
      result.ExampleComponent.generatedLatticeComponent()
        .createComponentViaFactory("Hello, world!")

    val exampleClass = component.callComponentAccessor<Callable<String>>("exampleClass")
    assertThat(exampleClass.call()).isEqualTo("Hello, world!")
  }
}
