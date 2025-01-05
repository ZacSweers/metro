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
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        val exampleClassFactory =
          ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
        val exampleClass = exampleClassFactory.invokeFactoryGet<Callable<String>>(2)
        assertThat(exampleClass.call()).isEqualTo("Hello, 2")
      }
  }

  @Test
  fun `assisted factory impl smoke test`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        val exampleClassFactory =
          ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

        val factoryImplClass = ExampleClassFactory.generatedAssistedFactoryImpl()
        val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
        val factoryImpl = factoryImplProvider()
        val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
        assertThat(exampleClass2.call()).isEqualTo("Hello, 2")
      }
  }

  @Test
  fun `default assisted factory is generated in FIR`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        val exampleClassFactory =
          ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

        val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
        val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
        val factoryImpl = factoryImplProvider()
        val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
        assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

        // Run through FIR's generated one too
        val exampleClass3 = invokeMain<Callable<String>>(factoryImpl, 3)
        assertThat(exampleClass3.call()).isEqualTo("Hello, 3")
      }
  }

  @Test
  fun `default assisted factory with default values`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        val exampleClassFactory =
          ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

        val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
        val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
        val factoryImpl = factoryImplProvider()
        val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
        assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

        // Run through FIR's generated one too
        val exampleClass3 = invokeMain<Callable<String>>(factoryImpl)
        assertThat(exampleClass3.call()).isEqualTo("Hello, 2")
      }
  }

  @Test
  fun `default assisted factory with custom identifiers`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        val exampleClassFactory =
          ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

        val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
        val factoryImplProvider = factoryImplClass.invokeCreateAsProvider(exampleClassFactory)
        val factoryImpl = factoryImplProvider()
        val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2, 3)
        assertThat(exampleClass2.call()).isEqualTo("Hello, 2 3")

        // Run through FIR's generated one too
        val exampleClass3 = invokeMain<Callable<String>>(factoryImpl, 2, 3)
        assertThat(exampleClass3.call()).isEqualTo("Hello, 2 3")
      }
  }

  @Test
  fun `assisted factory must target assisted inject types with matching assisted parameters`() {
      compile(
        source(
          """
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:6:7 Assisted parameter mismatch. Expected 1 assisted parameters but found 0.")
      }
  }

  @Test
  fun `assisted factory must target assisted inject types - missing constructor`() {
      compile(
        source(
          """
            class ExampleClass

            @AssistedFactory
            fun interface ExampleClassFactory {
              fun create(count: Int): ExampleClass
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      ) {
        assertDiagnostics("e: ExampleClass.kt:6:7 `@AssistedFactory` targets must have a single `@Inject`-annotated constructor.")
      }
  }

  @Test
  fun `assisted factories cannot be local`() {
      compile(
        source(
          """
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:18 Assisted factory classes cannot be local classes.")
      }
  }

  @Test
  fun `assisted factories cannot be protected`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:10:3 Assisted factory must be public or internal.
          """
            .trimIndent()
        )
      }
  }

  @Test
  fun `assisted factories cannot be private`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:10:3 Assisted factory must be public or internal.
          """
            .trimIndent()
        )
      }
  }

  @Test
  fun `assisted factories cannot be final`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:9 Assisted factory classes should be non-sealed abstract classes or interfaces.")
      }
  }

  @Test
  fun `assisted factories cannot be sealed classes`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:16 Assisted factory classes should be non-sealed abstract classes or interfaces.")
      }
  }

  @Test
  fun `assisted factories cannot be sealed interfaces`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:20 Assisted factory classes should be non-sealed abstract classes or interfaces.")
      }
  }

  @Test
  fun `assisted factories cannot be enums`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:14 Assisted factory classes should be non-sealed abstract classes or interfaces.")
      }
  }

  @Test
  fun `assisted factories cannot be annotation classes`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:10:20 Assisted factory classes should be non-sealed abstract classes or interfaces.
            e: ExampleClass.kt:11:5 Members are prohibited in annotation classes.
          """.trimIndent()
        )
      }
  }

  @Test
  fun `assisted factories cannot be objects`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:10 Assisted factory classes should be non-sealed abstract classes or interfaces.")
      }
  }

  @Test
  fun `assisted factories must have a single abstract function - absent`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
              @Assisted val count: Int,
            ) {
              @AssistedFactory
              interface Factory
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = COMPILATION_ERROR,
      ) {
        assertDiagnostics("e: ExampleClass.kt:10:13 @AssistedFactory classes must have exactly one abstract function but found none.")
      }
  }

  @Test
  fun `assisted factories must have a single abstract function - multiple`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:11:9 @AssistedFactory classes must have exactly one abstract function but found 2.
            e: ExampleClass.kt:12:9 @AssistedFactory classes must have exactly one abstract function but found 2.
          """.trimIndent()
        )
      }
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented from supertype`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:13:13 @AssistedFactory classes must have exactly one abstract function but found none.")
      }
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented in supertype`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:18:13 @AssistedFactory classes must have exactly one abstract function but found none.")
      }
  }

  @Test
  fun `assisted parameter mismatch - different count - one empty`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:6:7 Assisted parameter mismatch. Expected 0 assisted parameters but found 1.")
      }
  }

  @Test
  fun `assisted parameter mismatch - different count`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics("e: ExampleClass.kt:6:7 Assisted parameter mismatch. Expected 1 assisted parameters but found 2.")
      }
  }

  @Test
  fun `assisted parameter mismatch - different types`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
              Missing from factory: kotlin.Int
              Missing from factory: kotlin.String
          """.trimIndent()
        )
      }
  }

  @Test
  fun `assisted parameter mismatch - different identifiers`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
              Missing from factory: kotlin.Int (notcount)
              Missing from factory: kotlin.String (count)
          """.trimIndent()
        )
      }
  }

  @Test
  fun `assisted parameter mismatch - matching identifiers - different types`() {
      compile(
        source(
          """
            class ExampleClass @Inject constructor(
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
      ) {
        assertDiagnostics(
          """
            e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
              Missing from factory: kotlin.Int (count)
              Missing from factory: kotlin.String (count)
          """.trimIndent()
        )
      }
  }
}
