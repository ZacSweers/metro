/*
 * Copyright (C) 2025 Zac Sweers
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
package dev.zacsweers.lattice.sample

import dev.zacsweers.lattice.AssistedFactory
import dev.zacsweers.lattice.BindsInstance
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.createGraph
import dev.zacsweers.lattice.createGraphFactory
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
        @BindsInstance message: String,
        @Named("qualified") @BindsInstance qualifiedMessage: String,
      ): SimpleComponent
    }
  }

  class InjectedClass
  @Inject
  constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

  @Singleton
  class ScopedInjectedClass
  @Inject
  constructor(val message: String, @Named("qualified") val qualifiedMessage: String)

  @Inject
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
