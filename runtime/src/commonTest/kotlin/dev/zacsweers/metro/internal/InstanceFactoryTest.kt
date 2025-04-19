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

import kotlin.test.Test
import kotlin.test.assertEquals

class InstanceFactoryTest {
  @Test
  fun instanceFactory() {
    val instance = Any()
    val factory = InstanceFactory(instance)
    assertEquals(instance, factory())
    assertEquals(instance, factory())
    assertEquals(instance, factory())
  }

  // Since this is actually an unboxed value class at runtime, we can't actually assert same
  // instance here
  @Test
  fun nullableAlwaysReturnSameInstance() {
    assertEquals(InstanceFactory(null), InstanceFactory(null))
  }
}
