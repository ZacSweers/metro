package dev.zacsweers.lattice.sample

import dev.zacsweers.lattice.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent

class AnvilTest {
  abstract class AppScope private constructor()

  @Singleton
  @MergeComponent(AppScope::class)
  interface MergedComponent {

    val message: String
    val baseClass: BaseClass

    @Provides fun provideMessage(): String = "Hello, world!"
  }

  interface BaseClass {
    val message: String
  }

  @ContributesBinding(AppScope::class)
  class Impl @Inject constructor(override val message: String) : BaseClass

  @ContributesTo(AppScope::class) interface ContributedInterface

  @Test
  fun testMergedComponent() {
    val component = createGraph<MergedComponent>()
    assertEquals("Hello, world!", component.message)

    assertTrue(component is ContributedInterface)

    assertEquals("Hello, world!", component.baseClass.message)
  }
}
