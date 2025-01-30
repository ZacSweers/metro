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
package dev.zacsweers.metro.sample

import dagger.Component
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import jakarta.inject.Inject
import javax.inject.Singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Basic tests for mixed custom annotations. */
class MixedTest {
  @Singleton
  @Component
  interface SimpleComponent {
    val message: String
    @Named("qualified") val qualifiedMessage: String

    val injectedClass: InjectedClass
    val scopedInjectedClass: ScopedInjectedClass
    val assistedClassFactory: AssistedClass.Factory

    @Component.Factory
    interface Factory {
      fun create(
        @Provides message: String,
        @Provides @Named("qualified") qualifiedMessage: String,
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

  class AssistedClass @Inject constructor(@Assisted val assisted: String, val message: String) {
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
}
