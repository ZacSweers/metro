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
import dev.zacsweers.lattice.compiler.ExampleClass
import dev.zacsweers.lattice.compiler.ExampleClassFactory
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.generatedAssistedFactoryImpl
import dev.zacsweers.lattice.compiler.generatedFactoryClassAssisted
import dev.zacsweers.lattice.compiler.invokeCreate
import dev.zacsweers.lattice.compiler.invokeCreateAsProvider
import dev.zacsweers.lattice.compiler.invokeFactoryGet
import dev.zacsweers.lattice.compiler.invokeInstanceMethod
import dev.zacsweers.lattice.provider
import java.util.concurrent.Callable
import org.junit.Test

class AssistedFactoryTransformerTest : LatticeCompilerTest() {

  @Test
  fun `assisted inject class generates factory with custom get function`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Assisted
            import dev.zacsweers.lattice.annotations.AssistedInject
            import dev.zacsweers.lattice.annotations.AssistedFactory
            import java.util.concurrent.Callable

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
              val message: String,
            ) : Callable<String> {
              override fun call(): String = message + count
            }

            @AssistedFactory
            fun interface ExampleClassFactory {
              fun create(count: Int): ExampleClass
            }
          """
            .trimIndent(),
        )
      )

    val exampleClassFactory =
      result.ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
    val exampleClass = exampleClassFactory.invokeFactoryGet<Callable<String>>(2)
    assertThat(exampleClass.call()).isEqualTo("Hello, 2")
  }

  @Test
  fun `assisted factory impl smoke test`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Assisted
            import dev.zacsweers.lattice.annotations.AssistedInject
            import dev.zacsweers.lattice.annotations.AssistedFactory
            import java.util.concurrent.Callable

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
              val message: String,
            ) : Callable<String> {
              override fun call(): String = message + count
            }

            @AssistedFactory
            fun interface ExampleClassFactory {
              fun create(count: Int): ExampleClass
            }
          """
            .trimIndent(),
        )
      )

    val exampleClassFactory =
      result.ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

    val factoryImplClass = result.ExampleClassFactory.generatedAssistedFactoryImpl()
    val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
    val factoryImpl = factoryImplProvider()
    val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
    assertThat(exampleClass2.call()).isEqualTo("Hello, 2")
  }

  // TODO
  //  FIR errors
  //  misc abstract functions
}
