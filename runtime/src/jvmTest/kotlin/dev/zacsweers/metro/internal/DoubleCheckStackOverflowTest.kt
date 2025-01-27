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
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.atomicfu.atomic

/**
 * This test is only possible to run in the JVM as it uses [StackOverflowError].
 *
 * Other platforms have undefined behavior in stack overflow scenarios.
 */
class DoubleCheckStackOverflowTest {
  val doubleCheckReference = atomic<Provider<Any>?>(null)

  @Test
  fun `reentrance without condition throws stack overflow`() {
    val doubleCheck = DoubleCheck.provider(Provider { doubleCheckReference.value!!.invoke() })
    doubleCheckReference.value = doubleCheck
    assertFailsWith<StackOverflowError> { doubleCheck() }
  }
}
