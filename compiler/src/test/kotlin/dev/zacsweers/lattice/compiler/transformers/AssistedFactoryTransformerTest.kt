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
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.COMPILATION_ERROR
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleClass
import dev.zacsweers.lattice.compiler.ExampleClassFactory
import dev.zacsweers.lattice.compiler.Factory
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertContainsAll
import dev.zacsweers.lattice.compiler.assertDiagnostics
import dev.zacsweers.lattice.compiler.generatedAssistedFactoryImpl
import dev.zacsweers.lattice.compiler.generatedFactoryClassAssisted
import dev.zacsweers.lattice.compiler.invokeCreate
import dev.zacsweers.lattice.compiler.invokeCreateAsProvider
import dev.zacsweers.lattice.compiler.invokeFactoryGet
import dev.zacsweers.lattice.compiler.invokeInstanceMethod
import dev.zacsweers.lattice.compiler.invokeMain
import dev.zacsweers.lattice.provider
import java.util.concurrent.Callable
import org.junit.Test

class AssistedFactoryTransformerTest : LatticeCompilerTest() {

  @Test
  fun `assisted inject class generates factory with custom get function`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory
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
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory
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

  @Test
  fun `default assisted factory is generated in FIR`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory
            import java.util.concurrent.Callable

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
              val message: String,
            ) : Callable<String> {
              override fun call(): String = message + count
            }

            fun main(factory: ExampleClass.Factory, count: Int): ExampleClass {
              // Smoke test to ensure that the FIR-generated
              return factory.create(count = count)
            }
          """
            .trimIndent(),
        ),
        generateAssistedFactories = true,
      )

    val exampleClassFactory =
      result.ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

    val factoryImplClass = result.ExampleClass.Factory.generatedAssistedFactoryImpl()
    val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
    val factoryImpl = factoryImplProvider()
    val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
    assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

    // Run through FIR's generated one too
    val exampleClass3 = result.invokeMain<Callable<String>>(factoryImpl, 3)
    assertThat(exampleClass3.call()).isEqualTo("Hello, 3")
  }

  @Test
  fun `default assisted factory with default values`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory
            import java.util.concurrent.Callable

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int = 2,
              val message: String,
            ) : Callable<String> {
              override fun call(): String = message + count
            }

            fun main(factory: ExampleClass.Factory): ExampleClass {
              // Smoke test to ensure that the FIR-generated create() supports default args
              return factory.create()
            }
          """
            .trimIndent(),
        ),
        generateAssistedFactories = true,
      )

    val exampleClassFactory =
      result.ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

    val factoryImplClass = result.ExampleClass.Factory.generatedAssistedFactoryImpl()
    val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
    val factoryImpl = factoryImplProvider()
    val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
    assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

    // Run through FIR's generated one too
    val exampleClass3 = result.invokeMain<Callable<String>>(factoryImpl)
    assertThat(exampleClass3.call()).isEqualTo("Hello, 2")
  }

  @Test
  fun `default assisted factory with custom identifiers`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory
            import java.util.concurrent.Callable

            class ExampleClass @AssistedInject constructor(
              @Assisted("1") val count1: Int,
              @Assisted("2") val count2: Int,
              val message: String,
            ) : Callable<String> {
              override fun call(): String = message + count1 + " " + count2
            }

            fun main(factory: ExampleClass.Factory, count1: Int, count2: Int): ExampleClass {
              // Smoke test to ensure that the FIR-generated create() respects identifiers. Note the order switch
              return factory.create(count2 = count2, count1 = count1)
            }
          """
            .trimIndent(),
        ),
        generateAssistedFactories = true,
      )

    val exampleClassFactory =
      result.ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

    val factoryImplClass = result.ExampleClass.Factory.generatedAssistedFactoryImpl()
    val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
    val factoryImpl = factoryImplProvider()
    val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2, 3)
    assertThat(exampleClass2.call()).isEqualTo("Hello, 2 3")

    // Run through FIR's generated one too
    val exampleClass3 = result.invokeMain<Callable<String>>(factoryImpl, 2, 3)
    assertThat(exampleClass3.call()).isEqualTo("Hello, 2 3")
  }

  @Test
  fun `assisted factory must target assisted inject types`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @Inject constructor(
              val count: Int,
              val message: String,
            )

            @AssistedFactory
            fun interface ExampleClassFactory {
              fun create(count: Int): ExampleClass
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:8:7 `@AssistedFactory` targets must have a single `@AssistedInject`-annotated constructor."
    )
  }

  @Test
  fun `assisted factory must target assisted inject types - missing constructor`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass

            @AssistedFactory
            fun interface ExampleClassFactory {
              fun create(count: Int): ExampleClass
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:8:7 `@AssistedFactory` targets must have a single `@AssistedInject`-annotated constructor."
    )
  }

  @Test
  fun `assisted factories cannot be local`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass

            fun example() {
              @AssistedFactory
              abstract class ExampleClassFactory {
                abstract fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains("ExampleClass.kt:12:18 Assisted factory classes cannot be local classes.")
  }

  @Test
  fun `assisted factories cannot be protected`() {
    val result =
      compile(
        source(
          """
            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              protected fun interface Factory {
                fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleClass.kt:10:3 Assisted factory must be public or internal.
      """.trimIndent()
    )
  }

  @Test
  fun `assisted factories cannot be private`() {
    val result =
      compile(
        source(
          """
            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              private fun interface Factory {
                fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleClass.kt:10:3 Assisted factory must be public or internal.
      """.trimIndent()
    )
  }

  @Test
  fun `assisted factories cannot be final`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              class Factory {
                fun create(count: Int): ExampleClass {
                  throw NotImplementedError()
                }
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:9 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories cannot be sealed classes`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              sealed class Factory {
                abstract fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:16 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories cannot be sealed interfaces`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              sealed interface Factory {
                fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:20 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories cannot be enums`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              enum class Factory {
                INSTANCE {
                  override fun create(count: Int): ExampleClass {
                    throw NotImplementedError()
                  }
                };
                abstract fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:14 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories cannot be annotation classes`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              annotation class Factory {
                fun create(count: Int): ExampleClass {
                  throw NotImplementedError()
                }
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:20 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories cannot be objects`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              object Factory {
                fun create(count: Int): ExampleClass {
                  throw NotImplementedError()
                }
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:10 Assisted factory classes should be non-sealed abstract classes or interfaces."
    )
  }

  @Test
  fun `assisted factories must have a single abstract function - absent`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              interface Factory
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:12:13 @AssistedFactory classes must have exactly one abstract function but found none."
    )
  }

  @Test
  fun `assisted factories must have a single abstract function - multiple`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(count: Int): ExampleClass
                fun create2(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleClass.kt:13:9 @AssistedFactory classes must have exactly one abstract function but found 2.",
      "ExampleClass.kt:14:9 @AssistedFactory classes must have exactly one abstract function but found 2.",
    )
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented from supertype`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              interface BaseFactory {
                fun create(count: Int): ExampleClass
              }
              @AssistedFactory
              interface Factory : BaseFactory {
                override fun create(count: Int): ExampleClass {
                  throw NotImplementedError()
                }
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:15:13 @AssistedFactory classes must have exactly one abstract function but found none."
    )
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented in supertype`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Inject
            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              interface GrandParentFactory {
                fun create(count: Int): ExampleClass
              }
              interface ParentFactory : GrandParentFactory {
                override fun create(count: Int): ExampleClass {
                  throw NotImplementedError()
                }
              }
              @AssistedFactory
              interface Factory : ParentFactory
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:20:13 @AssistedFactory classes must have exactly one abstract function but found none."
    )
  }

  @Test
  fun `assisted parameter mismatch - different count - one empty`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:7:7 Assisted parameter mismatch. Expected 0 assisted parameters but found 1."
    )
  }

  @Test
  fun `assisted parameter mismatch - different count`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: Int,
              @Assisted val message: String,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleClass.kt:7:7 Assisted parameter mismatch. Expected 1 assisted parameters but found 2."
    )
  }

  @Test
  fun `assisted parameter mismatch - different types`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted val count: String,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleClass.kt:7:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
          Missing from factory: kotlin.Int
          Missing from factory: kotlin.String
      """
        .trimIndent()
    )
  }

  @Test
  fun `assisted parameter mismatch - different identifiers`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted("count") val count: String,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(@Assisted("notcount") count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleClass.kt:7:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
          Missing from factory: kotlin.Int (notcount)
          Missing from factory: kotlin.String (count)
      """
        .trimIndent()
    )
  }

  @Test
  fun `assisted parameter mismatch - matching identifiers - different types`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.Assisted
            import dev.zacsweers.lattice.AssistedInject
            import dev.zacsweers.lattice.AssistedFactory

            class ExampleClass @AssistedInject constructor(
              @Assisted("count") val count: String,
            ) {
              @AssistedFactory
              interface Factory {
                fun create(@Assisted("count") count: Int): ExampleClass
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleClass.kt:7:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
          Missing from factory: kotlin.Int (count)
          Missing from factory: kotlin.String (count)
      """
        .trimIndent()
    )
  }
}
