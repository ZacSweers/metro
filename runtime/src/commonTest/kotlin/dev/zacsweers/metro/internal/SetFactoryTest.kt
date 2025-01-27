/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.provider
import kotlin.test.Test
import kotlin.test.assertEquals

class SetFactoryTest {

  @Test
  fun invokesProvidersEveryTime() {
    val factory: Factory<Set<Int>> =
      SetFactory.builder<Int>(2, 2)
        .addProvider(incrementingIntProvider(0))
        .addProvider(incrementingIntProvider(10))
        .addCollectionProvider(incrementingIntSetProvider(20))
        .addCollectionProvider(incrementingIntSetProvider(30))
        .build()
    assertEquals(setOf(0, 10, 20, 21, 30, 31), factory())
    assertEquals(setOf(1, 11, 22, 23, 32, 33), factory())
    assertEquals(setOf(2, 12, 24, 25, 34, 35), factory())
  }

  companion object {
    private fun incrementingIntProvider(seed: Int): Provider<Int> {
      val value = SimpleCounter(seed)
      return provider { value.getAndIncrement() }
    }

    private fun incrementingIntSetProvider(seed: Int): Provider<Set<Int>> {
      val value = SimpleCounter(seed)
      return provider { setOf(value.getAndIncrement(), value.getAndIncrement()) }
    }
  }
}
