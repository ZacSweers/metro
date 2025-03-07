// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import dagger.BindsInstance
import dev.zacsweers.metro.createGraphFactory
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
    val baseClass2: BaseClass2

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

  interface BaseClass2 {
    val message: String
  }

  @ContributesBinding(AppScope::class, boundType = BaseClass2::class)
  class Impl2 @Inject constructor(override val message: String) : BaseClass2

  @ContributesTo(AppScope::class) interface ContributedInterface

  @Test
  fun testMergedComponent() {
    val component = createGraphFactory<MergedComponent.Factory>().create("Hello, world!")
    assertEquals("Hello, world!", component.message)

    assertTrue(component is ContributedInterface)

    assertEquals("Hello, world!", component.baseClass.message)
    assertEquals("Hello, world!", component.baseClass2.message)
  }
}
