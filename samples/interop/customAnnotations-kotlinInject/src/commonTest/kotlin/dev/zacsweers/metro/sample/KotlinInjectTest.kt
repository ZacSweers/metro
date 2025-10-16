// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides

/** Basic tests for kotlin-inject custom annotations. */
class KotlinInjectTest {
  @Singleton
  @DependencyGraph
  interface SimpleComponent {
    val message: String
    @Named("qualified") val qualifiedMessage: String
    val int: Int

    val injectedClass: InjectedClass
    val scopedInjectedClass: ScopedInjectedClass
    val assistedClassFactory: AssistedClass.Factory

    @Provides fun provideInt(): Int = 42

    @DependencyGraph.Factory
    interface Factory {
      fun create(
        @dev.zacsweers.metro.Provides message: String,
        @dev.zacsweers.metro.Provides @Named("qualified") qualifiedMessage: String,
      ): SimpleComponent
    }
  }

  @Inject class InjectedClass(val message: String, @Named("qualified") val qualifiedMessage: String)

  @Singleton
  @Inject
  class ScopedInjectedClass(val message: String, @Named("qualified") val qualifiedMessage: String)

  @AssistedInject
  class AssistedClass(@Assisted val assisted: String, val message: String) {
    @AssistedFactory
    interface Factory {
      fun create(assisted: String): AssistedClass
    }
  }

  @Test
  fun testSimpleComponent() {
    val component =
      createGraphFactory<SimpleComponent.Factory>()
        .create("Hello, world!", "Hello, qualified world!")
    assertEquals(42, component.int)
    assertEquals("Hello, world!", component.message)
    assertEquals("Hello, qualified world!", component.qualifiedMessage)

    val injectedClass = component.injectedClass
    // New instances for unscoped
    assertNotSame(injectedClass, component.injectedClass)
    assertEquals("Hello, world!", injectedClass.message)
    assertEquals("Hello, qualified world!", injectedClass.qualifiedMessage)

    val scopedInjectedClass = component.scopedInjectedClass
    // New instances for unscoped
    assertSame(scopedInjectedClass, component.scopedInjectedClass)
    assertEquals("Hello, world!", scopedInjectedClass.message)
    assertEquals("Hello, qualified world!", scopedInjectedClass.qualifiedMessage)

    val assistedClassFactory = component.assistedClassFactory
    val assistedClass = assistedClassFactory.create("assisted")
    assertEquals("Hello, world!", assistedClass.message)
    assertEquals("assisted", assistedClass.assisted)
  }

  @Singleton
  @Component
  interface NoArgComponent {
    val int: Int

    @Provides fun provideInt(): Int = 42
  }

  @Test
  fun testNoArg() {
    val component = createGraph<NoArgComponent>()
    assertEquals(42, component.int)
  }
}
