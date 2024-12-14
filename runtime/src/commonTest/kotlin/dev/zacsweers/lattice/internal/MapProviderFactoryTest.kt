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
package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.provider
import kotlin.test.Test
import kotlin.test.assertEquals

class MapProviderFactoryTest {
  @Test
  fun iterationOrder() {
    val p1: Provider<Int> = incrementingIntegerProvider(10)
    val p2: Provider<Int> = incrementingIntegerProvider(20)
    val p3: Provider<Int> = incrementingIntegerProvider(30)
    val p4: Provider<Int> = incrementingIntegerProvider(40)
    val p5: Provider<Int> = incrementingIntegerProvider(50)

    val factory: Factory<Map<String, Provider<Int>>> =
      MapProviderFactory.builder<String, Int>(4)
        .put("two", p2)
        .put("one", p1)
        .put("three", p3)
        .put("one", p5)
        .put("four", p4)
        .build()

    val expectedMap = LinkedHashMap<String, Provider<Int>>()
    expectedMap.put("two", p2)
    expectedMap.put("one", p1)
    expectedMap.put("three", p3)
    expectedMap.put("one", p5)
    expectedMap.put("four", p4)
    assertEquals(factory().entries.toList(), expectedMap.entries.toList())
  }

  companion object {
    private fun incrementingIntegerProvider(seed: Int): Provider<Int> {
      val counter = SimpleCounter(seed)
      return provider { counter.getAndIncrement() }
    }
  }
}
