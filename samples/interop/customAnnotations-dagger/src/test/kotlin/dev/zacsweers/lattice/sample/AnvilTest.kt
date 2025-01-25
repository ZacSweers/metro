package dev.zacsweers.lattice.sample

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import dagger.BindsInstance
import dagger.Component
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
