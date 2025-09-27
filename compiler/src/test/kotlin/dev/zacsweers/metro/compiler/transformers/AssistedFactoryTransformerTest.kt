// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.COMPILATION_ERROR
import dev.zacsweers.metro.compiler.ExampleClass
import dev.zacsweers.metro.compiler.ExampleClassFactory
import dev.zacsweers.metro.compiler.Factory
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFactoryInvoke
import dev.zacsweers.metro.compiler.generatedAssistedFactoryImpl
import dev.zacsweers.metro.compiler.generatedFactoryClassAssisted
import dev.zacsweers.metro.compiler.invokeCreate
import dev.zacsweers.metro.compiler.invokeCreateAsProvider
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.compiler.invokeMain
import dev.zacsweers.metro.provider
import java.util.concurrent.Callable
import org.junit.Ignore
import org.junit.Test

class AssistedFactoryTransformerTest : MetroCompilerTest() {

  @Test
  fun `assisted inject class generates factory with custom get function`() {
    compile(
      source(
        """
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
          .trimIndent()
      )
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
      val exampleClass = exampleClassFactory.callFactoryInvoke<Callable<String>>(2)
      assertThat(exampleClass.call()).isEqualTo("Hello, 2")
    }
  }

  @Test
  fun `assisted factory impl smoke test`() {
    compile(
      source(
        """
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
          .trimIndent()
      )
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

      val factoryImplClass = ExampleClassFactory.generatedAssistedFactoryImpl()
      val factoryImplProvider = factoryImplClass.invokeCreateAsProvider<Any>(exampleClassFactory)
      val factoryImpl = factoryImplProvider()
      val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
      assertThat(exampleClass2.call()).isEqualTo("Hello, 2")
    }
  }

  @Ignore("Ignored until we merge the assisted FIR generators")
  @Test
  fun `default assisted factory is generated in FIR`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      generateAssistedFactories = true,
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

      val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
      val factoryImplProvider = factoryImplClass.invokeCreateAsProvider<Any>(exampleClassFactory)
      val factoryImpl = factoryImplProvider()
      val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
      assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

      // Run through FIR's generated one too
      val exampleClass3 = invokeMain<Callable<String>>(factoryImpl, 3)
      assertThat(exampleClass3.call()).isEqualTo("Hello, 3")
    }
  }

  @Ignore("Ignored until we merge the assisted FIR generators")
  @Test
  fun `default assisted factory with default values`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      generateAssistedFactories = true,
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

      val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
      val factoryImplProvider = factoryImplClass.invokeCreateAsProvider<Any>(exampleClassFactory)
      val factoryImpl = factoryImplProvider()
      val exampleClass2 = factoryImpl.invokeInstanceMethod<Callable<String>>("create", 2)
      assertThat(exampleClass2.call()).isEqualTo("Hello, 2")

      // Run through FIR's generated one too
      val exampleClass3 = invokeMain<Callable<String>>(factoryImpl)
      assertThat(exampleClass3.call()).isEqualTo("Hello, 2")
    }
  }

  @Ignore("Ignored until we merge the assisted FIR generators")
  @Test
  fun `default assisted factory with custom identifiers`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      generateAssistedFactories = true,
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })

      val factoryImplClass = ExampleClass.Factory.generatedAssistedFactoryImpl()
      val factoryImplProvider = factoryImplClass.invokeCreateAsProvider<Any>(exampleClassFactory)
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
          class ExampleClass @AssistedInject constructor(
            val count: Int,
            val message: String,
          )

          @AssistedFactory
          fun interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from factory: kotlin.Int
        """
          .trimIndent()
      )
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:27 Invalid return type: test.ExampleClass. `@AssistedFactory` target classes must have a single `@AssistedInject`-annotated constructor or be annotated `@AssistedInject` with only a primary constructor."
      )
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/364#issuecomment-2841469320
  @Test
  fun `assisted factory must target assisted inject types - missing return type`() {
    compile(
      source(
        """
          @AssistedFactory
          fun interface ExampleClassFactory {
            fun create(count: Int)
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClassFactory.kt:8:7 Invalid return type: kotlin.Unit. `@AssistedFactory` target classes must have a single `@AssistedInject`-annotated constructor or be annotated `@AssistedInject` with only a primary constructor."
      )
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:18 @AssistedFactory declarations cannot be local classes."
      )
    }
  }

  @Test
  fun `assisted factories cannot be protected`() {
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
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:3 @AssistedFactory declarations must be public or internal."
      )
    }
  }

  @Test
  fun `assisted factories cannot be private`() {
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
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:3 @AssistedFactory declarations must be public or internal."
      )
    }
  }

  @Test
  fun `assisted factories cannot be final`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:9 @AssistedFactory declarations should be non-sealed abstract classes or interfaces."
      )
    }
  }

  @Test
  fun `assisted factories cannot be sealed classes`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
          ) {
            @AssistedFactory
            sealed class Factory {
              abstract fun create(count: Int): ExampleClass
            }
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:16 @AssistedFactory declarations should be non-sealed abstract classes or interfaces."
      )
    }
  }

  @Test
  fun `assisted factories cannot be sealed interfaces`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
          ) {
            @AssistedFactory
            sealed interface Factory {
              fun create(count: Int): ExampleClass
            }
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:20 @AssistedFactory declarations should be non-sealed abstract classes or interfaces."
      )
    }
  }

  @Test
  fun `assisted factories cannot be enums`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:14 @AssistedFactory declarations should be non-sealed abstract classes or interfaces."
      )
    }
  }

  @Test
  fun `assisted factories cannot be annotation classes`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
          ) {
            @AssistedFactory
            annotation class Factory
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:10:20 @AssistedFactory declarations should be non-sealed abstract classes or interfaces.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted factories cannot be objects`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:10 @AssistedFactory declarations should be non-sealed abstract classes or interfaces."
      )
    }
  }

  @Test
  fun `assisted factories must have a single abstract function - absent`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
          ) {
            @AssistedFactory
            interface Factory
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:13 @AssistedFactory declarations must have exactly one abstract function but found none."
      )
    }
  }

  @Test
  fun `assisted factories must have a single abstract function - multiple`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:11:9 @AssistedFactory declarations must have exactly one abstract function but found 2.
          e: ExampleClass.kt:12:9 @AssistedFactory declarations must have exactly one abstract function but found 2.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented from supertype`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:13:13 @AssistedFactory declarations must have exactly one abstract function but found none."
      )
    }
  }

  @Test
  fun `assisted factories must have a single abstract function - implemented in supertype`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:18:13 @AssistedFactory declarations must have exactly one abstract function but found none."
      )
    }
  }

  @Test
  fun `assisted parameter mismatch - different count - one empty`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
          ) {
            @AssistedFactory
            interface Factory {
              fun create(): ExampleClass
            }
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from constructor: kotlin.Int
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted parameter mismatch - different count`() {
    compile(
      source(
        """
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
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from constructor: kotlin.String
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted parameter mismatch - different types`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: String,
          ) {
            @AssistedFactory
            interface Factory {
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
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from factory: kotlin.Int
            Missing from constructor: kotlin.String
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted parameter mismatch - different identifiers`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted("count") val count: String,
          ) {
            @AssistedFactory
            interface Factory {
              fun create(@Assisted("notcount") count: Int): ExampleClass
            }
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from factory: kotlin.Int (notcount)
            Missing from constructor: kotlin.String (count)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted parameter mismatch - matching identifiers - different types`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted("count") val count: String,
          ) {
            @AssistedFactory
            interface Factory {
              fun create(@Assisted("count") count: Int): ExampleClass
            }
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:6:7 Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:
            Missing from factory: kotlin.Int (count)
            Missing from constructor: kotlin.String (count)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted parameters in later order work`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClassFactory: ExampleClass.Factory

          @Provides val int: Int get() = 0
        }

        class ExampleClass @AssistedInject constructor(
          val count: Int,
          @Assisted val text: String,
        ) {
          fun template(): String = text + count

          @AssistedFactory
          interface Factory {
            fun create(@Assisted text: String): ExampleClass
          }
        }

        fun main(text: String): String {
          val graph = createGraph<ExampleGraph>()
          val exampleClass = graph.exampleClassFactory.create(text)
          return exampleClass.template()
        }
        """
          .trimIndent()
      )
    ) {
      val result = invokeMain<String>("hello ", mainClass = "test.ExampleGraphKt")
      assertThat(result).isEqualTo("hello 0")
    }
  }

  @Test
  fun `assisted factory with additional non abstract methods work`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          ) : Callable<String> {
            override fun call(): String = message + count
          }

          @AssistedFactory
          interface ExampleClassFactory {
            fun foo(): ExampleClass = create(0)
            fun bar() {}

            fun create(count: Int): ExampleClass
          }
        """
          .trimIndent()
      )
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
      val exampleClass = exampleClassFactory.callFactoryInvoke<Callable<String>>(2)
      assertThat(exampleClass.call()).isEqualTo("Hello, 2")
    }
  }

  @Test
  fun `assisted factory with protected method works`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          ) : Callable<String> {
            override fun call(): String = message + count
          }

          @AssistedFactory
          abstract class ExampleClassFactory {
            protected abstract fun create(count: Int): ExampleClass
          }
        """
          .trimIndent()
      )
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
      val exampleClass = exampleClassFactory.callFactoryInvoke<Callable<String>>(2)
      assertThat(exampleClass.call()).isEqualTo("Hello, 2")
    }
  }

  @Test
  fun `assisted factory with generic method works`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          ) : Callable<String> {
            override fun call(): String = message + count
          }

          interface BaseFactory<B, T> {
            fun create(b: B): T
          }

          @AssistedFactory
          abstract class ExampleClassFactory : BaseFactory<Int, ExampleClass>
        """
          .trimIndent()
      )
    ) {
      val exampleClassFactory =
        ExampleClass.generatedFactoryClassAssisted().invokeCreate(provider { "Hello, " })
      val exampleClass = exampleClassFactory.callFactoryInvoke<Callable<String>>(2)
      assertThat(exampleClass.call()).isEqualTo("Hello, 2")
    }
  }

  @Test
  fun `assisted types cannot be depended on directly - class`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          )

          @AssistedFactory
          interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }

          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClassFactory
            val exampleClass: Consumer

            @Provides val string: String get() = "Hello, world!"
          }

          @Inject
          class Consumer(val exampleClass: ExampleClass)
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:25:34 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.exampleClass'. You must inject a corresponding @AssistedFactory type or provide a qualified instance on the graph instead.

          (Hint)
          It looks like the @AssistedFactory for 'test.ExampleClass' may be 'test.ExampleClassFactory'.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted types cannot be depended on directly - provider`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          )

          @AssistedFactory
          interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }

          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClassFactory
            val exampleClass: Consumer

            @Provides val string: String get() = "Hello, world!"
            @Provides fun provideConsumer(exampleClass: ExampleClass): Consumer = Consumer(exampleClass)
          }

          class Consumer(val exampleClass: ExampleClass)
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:22:47 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.exampleClass'. You must inject a corresponding @AssistedFactory type or provide a qualified instance on the graph instead.

          (Hint)
          It looks like the @AssistedFactory for 'test.ExampleClass' may be 'test.ExampleClassFactory'.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted types cannot be depended on directly - member`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          )

          @AssistedFactory
          interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }

          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClassFactory
            fun inject(exampleClass: Consumer)

            @Provides val string: String get() = "Hello, world!"
          }

          class Consumer {
            @Inject lateinit var exampleClass: ExampleClass
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$$"""
          e: ExampleClass.kt:16:1 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.$$MetroGraph.inject'. You must inject a corresponding @AssistedFactory type instead.

(Hint)
It looks like the @AssistedFactory for 'test.ExampleClass' is 'test.ExampleClassFactory'.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted types cannot be depended on directly - accessor`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          )

          @AssistedFactory
          interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }

          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClassFactory
            val exampleClass: ExampleClass

            @Provides val string: String get() = "Hello, world!"
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:19:21 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.exampleClass'. You must inject a corresponding @AssistedFactory type or provide a qualified instance on the graph instead.

          (Hint)
          It looks like the @AssistedFactory for 'test.ExampleClass' may be 'test.ExampleClassFactory'.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted types cannot be depended on directly - accessor with no other ref`() {
    compile(
      source(
        """
          class ExampleClass @AssistedInject constructor(
            @Assisted val count: Int,
            val message: String,
          )

          @AssistedFactory
          interface ExampleClassFactory {
            fun create(count: Int): ExampleClass
          }

          @DependencyGraph
          interface ExampleGraph {
            // The omission of ExampleClassFactory from accessors is intentional, prevents
            // regression of https://github.com/ZacSweers/metro/issues/538 caused by existence
            // of other dependents short-circuiting the check on roots
            val exampleClass: ExampleClass

            @Provides val string: String get() = "Hello, world!"
          }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ExampleClass.kt:21:21 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.exampleClass'. You must inject a corresponding @AssistedFactory type or provide a qualified instance on the graph instead.

          (Hint)
          It looks like the @AssistedFactory for 'test.ExampleClass' may be 'test.ExampleClassFactory'.
        """
          .trimIndent()
      )
    }
  }
}
