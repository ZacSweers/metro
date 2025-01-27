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

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import dagger.BindsInstance
import dev.zacsweers.lattice.createGraphFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnvilTest {
  abstract class AppScope private constructor()

  @Singleton
  @MergeComponent(AppScope::class)
  interface MergedComponent {

    val message: String
    val baseClass: BaseClass

    @MergeComponent.Factory
    interface Factory {
      fun create(@BindsInstance message: String): MergedComponent
    }
  }

  interface BaseClass {
    val message: String
  }

  @ContributesBinding(AppScope::class)
  class Impl @Inject constructor(override val message: String) : BaseClass

  @ContributesTo(AppScope::class) interface ContributedInterface

  @Test
  fun testMergedComponent() {
    val component = createGraphFactory<MergedComponent.Factory>().create("Hello, world!")
    assertEquals("Hello, world!", component.message)

    assertTrue(component is ContributedInterface)

    assertEquals("Hello, world!", component.baseClass.message)
  }
}
