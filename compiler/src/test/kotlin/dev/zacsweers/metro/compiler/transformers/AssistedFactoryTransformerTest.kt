// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.COMPILATION_ERROR
import dev.zacsweers.metro.compiler.ExampleClass
import dev.zacsweers.metro.compiler.ExampleClassFactory
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.generatedAssistedFactoryImpl
import dev.zacsweers.metro.compiler.generatedFactoryClassAssisted
import dev.zacsweers.metro.compiler.invokeCreate
import dev.zacsweers.metro.compiler.invokeCreateAsProvider
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.provider
import java.util.concurrent.Callable
import org.junit.Test

class AssistedFactoryTransformerTest : MetroCompilerTest() {

  // Reflection-heavy, requires a bit more work
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
          Missing from factory: kotlin.Int
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
          Missing from factory: kotlin.String
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
          Missing from factory: kotlin.String
          Missing from constructor: kotlin.Int
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
          Missing from factory: kotlin.String (count)
          Missing from constructor: kotlin.Int (notcount)
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
          Missing from factory: kotlin.String (count)
          Missing from constructor: kotlin.Int (count)
        """
          .trimIndent()
      )
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
        """
        e: ExampleClass.kt:25:24 [Metro/InvalidBinding] 'test.ExampleClass' uses assisted injection and cannot be injected directly into 'test.Consumer.exampleClass'. You must inject a corresponding @AssistedFactory type instead.

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
