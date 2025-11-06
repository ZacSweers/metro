// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.assistedinject.Assisted
import com.google.inject.assistedinject.AssistedInject
import com.google.inject.name.Named
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides as MetroProvides
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class GuiceTest {
  @Singleton
  @DependencyGraph
  interface SimpleGraph {
    val message: String
    @get:Named("qualified") val qualifiedMessage: String
    val int: Int

    val injectedClass: InjectedClass
    val scopedInjectedClass: ScopedInjectedClass
    val assistedClassFactory: AssistedClass.Factory
    val messageProvider: Provider<String>

    @Provides fun provideInt(): Int = 42

    @DependencyGraph.Factory
    interface Factory {
      fun create(
        @MetroProvides message: String,
        @MetroProvides @Named("qualified") qualifiedMessage: String,
      ): SimpleGraph
    }
  }

  class InjectedClass
  @Inject
  constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

  @Singleton
  class ScopedInjectedClass
  @Inject
  constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

  class AssistedClass
  @AssistedInject
  constructor(@Assisted val assisted: String, val injectedClass: InjectedClass) {
    @AssistedFactory
    interface Factory {
      fun create(assisted: String): AssistedClass
    }
  }

  @Test
  fun guiceTest() {
    val graph =
      createGraphFactory<SimpleGraph.Factory>().create("Hello, world!", "Hello, qualified world!")
    assertEquals(42, graph.int)
    assertEquals("Hello, world!", graph.message)
    assertEquals("Hello, qualified world!", graph.qualifiedMessage)

    // Test Provider<T>
    assertEquals("Hello, world!", graph.messageProvider.get())

    // New instances for unscoped
    val injectedClass = graph.injectedClass
    assertNotSame(injectedClass, graph.injectedClass)
    assertEquals("Hello, world!", injectedClass.message)
    assertEquals("Hello, qualified world!", injectedClass.qualifiedMessage)

    // Same instance for scoped
    val scopedInjectedClass = graph.scopedInjectedClass
    assertSame(scopedInjectedClass, graph.scopedInjectedClass)
    assertEquals("Hello, world!", scopedInjectedClass.message)
    assertEquals("Hello, qualified world!", scopedInjectedClass.qualifiedMessage)

    val assistedClassFactory = graph.assistedClassFactory
    val assistedClass = assistedClassFactory.create("assisted")
    assertEquals("Hello, world!", assistedClass.injectedClass.message)
    assertEquals("assisted", assistedClass.assisted)
  }
}
